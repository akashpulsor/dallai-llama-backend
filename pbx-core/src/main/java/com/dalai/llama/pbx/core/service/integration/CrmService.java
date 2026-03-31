package com.dalai.llama.pbx.core.service.integration;

import com.dalai.llama.pbx.core.domain.entity.campaign.Campaign;
import com.dalai.llama.pbx.core.domain.entity.campaign.CampaignContact;
import com.dalai.llama.pbx.core.domain.entity.integration.CrmIntegration;
import com.dalai.llama.pbx.core.domain.enums.ContactStatus;
import com.dalai.llama.pbx.core.repository.campaign.CampaignContactRepository;
import com.dalai.llama.pbx.core.repository.campaign.CampaignRepository;
import com.dalai.llama.pbx.core.repository.integration.CrmIntegrationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class CrmService {

    private final CrmIntegrationRepository crmRepo;
    private final CampaignRepository campaignRepo;
    private final CampaignContactRepository contactRepo;
    private final RestTemplate restTemplate;



    public CrmIntegration create(Map<String, Object> body) {
        CrmIntegration crm = CrmIntegration.builder()
                .tenantId(UUID.fromString(body.get("tenant_id").toString()))
                .subscriptionId(UUID.fromString(body.get("subscription_id").toString()))
                .provider((String) body.get("provider"))
                .name((String) body.get("name"))
                .apiUrl((String) body.get("api_url"))
                .apiKey((String) body.get("api_key"))
                .clientId((String) body.get("client_id"))
                .clientSecret((String) body.get("client_secret"))
                .refreshToken((String) body.get("refresh_token"))
                .syncContacts(body.get("sync_contacts") instanceof Boolean b ? b : true)
                .syncCalls(body.get("sync_calls") instanceof Boolean b ? b : true)
                .syncNotes(body.get("sync_notes") instanceof Boolean b ? b : true)
                .build();
        return crmRepo.save(crm);
    }

    public List<CrmIntegration> getByTenantId(UUID tenantId) {
        return crmRepo.findByTenantId(tenantId);
    }

    public Optional<CrmIntegration> getById(UUID id) {
        return crmRepo.findById(id);
    }

    public void delete(UUID id) {
        crmRepo.deleteById(id);
    }

    /**
     * Test CRM connection by hitting a lightweight endpoint.
     */
    public Map<String, Object> testConnection(UUID crmId) {
        CrmIntegration crm = crmRepo.findById(crmId).orElseThrow();
        try {
            HttpHeaders headers = buildHeaders(crm);
            String testUrl = getTestUrl(crm);

            ResponseEntity<String> response = restTemplate.exchange(
                    testUrl, HttpMethod.GET, new HttpEntity<>(headers), String.class);

            boolean success = response.getStatusCode().is2xxSuccessful();
            if (success) {
                crm.setIsActive(true);
                crmRepo.save(crm);
            }
            return Map.of("success", success, "status", response.getStatusCode().value());
        } catch (Exception e) {
            return Map.of("success", false, "error", e.getMessage());
        }
    }

    /**
     * Fetch available lists/views/segments from CRM.
     * Returns list of { id, name, count } objects.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> fetchLists(UUID crmId) {
        CrmIntegration crm = crmRepo.findById(crmId).orElseThrow();
        HttpHeaders headers = buildHeaders(crm);

        String url = switch (crm.getProvider().toUpperCase()) {
            case "SALESFORCE" -> crm.getApiUrl() + "/services/data/v58.0/query?q=SELECT+Id,Name+FROM+ListView+WHERE+SobjectType='Lead'";
            case "HUBSPOT" -> "https://api.hubapi.com/crm/v3/lists";
            case "ZOHO" -> crm.getApiUrl() + "/crm/v2/settings/custom_views?module=Leads";
            case "FRESHSALES" -> crm.getApiUrl() + "/api/contacts/filters";
            case "PIPEDRIVE" -> crm.getApiUrl() + "/v1/filters?type=people";
            default -> crm.getApiUrl() + "/lists";
        };

        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
            // Parse provider-specific response into normalized format
            return normalizeListsResponse(crm.getProvider(), response.getBody());
        } catch (Exception e) {
            log.error("Failed to fetch CRM lists: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Import leads from a CRM list into a campaign.
     * Fetches contacts from CRM, maps fields, creates CampaignContact rows.
     */
    @Transactional
    @SuppressWarnings("unchecked")
    public int importLeadsFromCrm(UUID crmId, UUID campaignId, String listId) {
        CrmIntegration crm = crmRepo.findById(crmId).orElseThrow();
        Campaign campaign = campaignRepo.findById(campaignId).orElseThrow();
        HttpHeaders headers = buildHeaders(crm);

        // Fetch leads from CRM
        String url = getLeadsUrl(crm, listId);
        ResponseEntity<Map> response = restTemplate.exchange(
                url, HttpMethod.GET, new HttpEntity<>(headers), Map.class);

        List<Map<String, Object>> leads = normalizeLeadsResponse(crm.getProvider(), response.getBody());
        int imported = 0;

        for (Map<String, Object> lead : leads) {
            String phone = extractPhone(lead, crm);
            if (phone == null || phone.isBlank()) continue;

            // Dedup check
            if (contactRepo.existsByCampaignAndPhoneNumber(campaign, phone)) continue;

            CampaignContact contact = CampaignContact.builder()
                    .campaign(campaign)
                    .tenantId(campaign.getTenantId())
                    .phoneNumber(phone)
                    .name(extractField(lead, crm, "name"))
                    .email(extractField(lead, crm, "email"))
                    .company(extractField(lead, crm, "company"))
                    .crmId(String.valueOf(lead.get("id")))
                    .crmProvider(crm.getProvider())
                    .source("CRM")
                    .customData(lead)
                    .status(ContactStatus.PENDING)
                    .build();

            contactRepo.save(contact);
            imported++;
        }

        // Update campaign total
        campaign.setTotalContacts((campaign.getTotalContacts() != null ? campaign.getTotalContacts() : 0) + imported);
        campaignRepo.save(campaign);

        crm.setLastSyncAt(Instant.now());
        crmRepo.save(crm);

        log.info("CRM import: {} leads from {} list={} into campaign={}",
                imported, crm.getProvider(), listId, campaignId);
        return imported;
    }

    /**
     * Push call result back to CRM — create activity/note on the lead.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> pushCallResult(UUID crmId, String contactIdStr) {
        CrmIntegration crm = crmRepo.findById(crmId).orElseThrow();
        CampaignContact contact = contactRepo.findById(UUID.fromString(contactIdStr)).orElseThrow();

        if (contact.getCrmId() == null) {
            return Map.of("success", false, "error", "No CRM ID on contact");
        }

        HttpHeaders headers = buildHeaders(crm);
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> activity = Map.of(
                "subject", "Outbound Call — " + contact.getCampaign().getName(),
                "status", contact.getStatus().name(),
                "duration", contact.getDurationSeconds() != null ? contact.getDurationSeconds() : 0,
                "disposition", contact.getDisposition() != null ? contact.getDisposition() : "",
                "notes", contact.getNotes() != null ? contact.getNotes() : "",
                "call_id", contact.getCallId() != null ? contact.getCallId() : ""
        );

        try {
            String url = getActivityUrl(crm, contact.getCrmId());
            restTemplate.exchange(url, HttpMethod.POST,
                    new HttpEntity<>(activity, headers), Map.class);
            return Map.of("success", true, "crm_id", contact.getCrmId());
        } catch (Exception e) {
            log.error("CRM push failed: {}", e.getMessage());
            return Map.of("success", false, "error", e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════
    // HELPERS — provider-specific URL/header builders
    // ═══════════════════════════════════════════════════════════

    private HttpHeaders buildHeaders(CrmIntegration crm) {
        HttpHeaders headers = new HttpHeaders();
        switch (crm.getProvider().toUpperCase()) {
            case "SALESFORCE" -> {
                refreshSalesforceToken(crm);
                headers.setBearerAuth(crm.getAccessToken());
            }
            case "HUBSPOT" -> headers.setBearerAuth(crm.getApiKey());
            case "ZOHO" -> {
                headers.set("Authorization", "Zoho-oauthtoken " + crm.getAccessToken());
            }
            case "FRESHSALES" -> {
                headers.set("Authorization", "Token token=" + crm.getApiKey());
            }
            case "PIPEDRIVE" -> {} // API token goes as query param
            default -> {
                if (crm.getApiKey() != null) headers.setBearerAuth(crm.getApiKey());
            }
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private String getTestUrl(CrmIntegration crm) {
        return switch (crm.getProvider().toUpperCase()) {
            case "SALESFORCE" -> crm.getApiUrl() + "/services/data/v58.0/sobjects";
            case "HUBSPOT" -> "https://api.hubapi.com/crm/v3/objects/contacts?limit=1";
            case "ZOHO" -> crm.getApiUrl() + "/crm/v2/org";
            case "FRESHSALES" -> crm.getApiUrl() + "/api/contacts?per_page=1";
            case "PIPEDRIVE" -> crm.getApiUrl() + "/v1/users/me?api_token=" + crm.getApiKey();
            default -> crm.getApiUrl() + "/health";
        };
    }

    private String getLeadsUrl(CrmIntegration crm, String listId) {
        return switch (crm.getProvider().toUpperCase()) {
            case "SALESFORCE" -> crm.getApiUrl() + "/services/data/v58.0/query?q=SELECT+Id,Name,Phone,Email,Company+FROM+Lead+WHERE+Id+IN+(SELECT+LeadId+FROM+ListViewRecord+WHERE+ListViewId='" + listId + "')";
            case "HUBSPOT" -> "https://api.hubapi.com/crm/v3/lists/" + listId + "/memberships?limit=500";
            case "ZOHO" -> crm.getApiUrl() + "/crm/v2/Leads?cvid=" + listId + "&per_page=200";
            case "FRESHSALES" -> crm.getApiUrl() + "/api/contacts/view/" + listId;
            case "PIPEDRIVE" -> crm.getApiUrl() + "/v1/persons?filter_id=" + listId + "&limit=500&api_token=" + crm.getApiKey();
            default -> crm.getApiUrl() + "/leads?list_id=" + listId;
        };
    }

    private String getActivityUrl(CrmIntegration crm, String crmLeadId) {
        return switch (crm.getProvider().toUpperCase()) {
            case "SALESFORCE" -> crm.getApiUrl() + "/services/data/v58.0/sobjects/Task";
            case "HUBSPOT" -> "https://api.hubapi.com/crm/v3/objects/calls";
            case "ZOHO" -> crm.getApiUrl() + "/crm/v2/Calls";
            case "FRESHSALES" -> crm.getApiUrl() + "/api/contacts/" + crmLeadId + "/notes";
            case "PIPEDRIVE" -> crm.getApiUrl() + "/v1/activities?api_token=" + crm.getApiKey();
            default -> crm.getApiUrl() + "/activities";
        };
    }

    private void refreshSalesforceToken(CrmIntegration crm) {
        if (crm.getAccessToken() != null && crm.getTokenExpiresAt() != null
                && crm.getTokenExpiresAt().isAfter(Instant.now())) {
            return; // Token still valid
        }

        try {
            String tokenUrl = crm.getApiUrl().replace("/services", "") + "/services/oauth2/token";
            Map<String, String> tokenRequest = Map.of(
                    "grant_type", "refresh_token",
                    "client_id", crm.getClientId(),
                    "client_secret", crm.getClientSecret(),
                    "refresh_token", crm.getRefreshToken()
            );

            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.postForObject(tokenUrl, tokenRequest, Map.class);
            if (response != null) {
                crm.setAccessToken((String) response.get("access_token"));
                crm.setTokenExpiresAt(Instant.now().plusSeconds(7200));
                crmRepo.save(crm);
            }
        } catch (Exception e) {
            log.error("Salesforce token refresh failed: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> normalizeListsResponse(String provider, Map<String, Object> body) {
        if (body == null) return List.of();
        // Each provider has different response format — normalize to [{id, name, count}]
        // Simplified — real implementation would parse each provider's format
        List<Map<String, Object>> result = new ArrayList<>();
        Object records = body.get("records") != null ? body.get("records") :
                body.get("lists") != null ? body.get("lists") :
                        body.get("data") != null ? body.get("data") : List.of();
        if (records instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> raw) {
                    Map<String, Object> m = new HashMap<>();
                    raw.forEach((k, v) -> m.put(String.valueOf(k), v));

                    result.add(Map.of(
                            "id", String.valueOf(m.getOrDefault("id", m.getOrDefault("Id", ""))),
                            "name", String.valueOf(m.getOrDefault("name", m.getOrDefault("Name", "Unknown"))),
                            "count", m.getOrDefault("count", m.getOrDefault("size", 0))
                    ));
                }
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> normalizeLeadsResponse(String provider, Map<String, Object> body) {
        if (body == null) return List.of();
        Object records = body.get("records") != null ? body.get("records") :
                body.get("results") != null ? body.get("results") :
                        body.get("data") != null ? body.get("data") : List.of();
        if (records instanceof List<?> list) {
            return list.stream()
                    .filter(i -> i instanceof Map)
                    .map(i -> (Map<String, Object>) i)
                    .toList();
        }
        return List.of();
    }

    private String extractPhone(Map<String, Object> lead, CrmIntegration crm) {
        // Check field mapping first, then common field names
        String mappedField = crm.getFieldMapping().get("phone");
        if (mappedField != null && lead.get(mappedField) != null) {
            return lead.get(mappedField).toString();
        }
        for (String f : List.of("Phone", "phone", "MobilePhone", "mobile", "phone_number")) {
            if (lead.get(f) != null) return lead.get(f).toString();
        }
        return null;
    }

    private String extractField(Map<String, Object> lead, CrmIntegration crm, String field) {
        String mappedField = crm.getFieldMapping().get(field);
        if (mappedField != null && lead.get(mappedField) != null) {
            return lead.get(mappedField).toString();
        }
        // Try common names
        return switch (field) {
            case "name" -> {
                for (String f : List.of("Name", "name", "FirstName", "first_name")) {
                    if (lead.get(f) != null) yield lead.get(f).toString();
                }
                yield null;
            }
            case "email" -> {
                for (String f : List.of("Email", "email")) {
                    if (lead.get(f) != null) yield lead.get(f).toString();
                }
                yield null;
            }
            case "company" -> {
                for (String f : List.of("Company", "company", "company_name")) {
                    if (lead.get(f) != null) yield lead.get(f).toString();
                }
                yield null;
            }
            default -> null;
        };
    }
}