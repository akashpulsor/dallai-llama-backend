package com.dalai.llama.pbx.core.controller.campaign;

import com.dalai.llama.pbx.core.domain.entity.campaign.CampaignContact;
import com.dalai.llama.pbx.core.domain.entity.campaign.DncEntry;
import com.dalai.llama.pbx.core.domain.enums.ContactStatus;
import com.dalai.llama.pbx.core.domain.enums.DncSource;
import com.dalai.llama.pbx.core.service.campaign.ContactService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/contacts")
@RequiredArgsConstructor
public class ContactController {

    private final ContactService contactService;

    @PostMapping("/campaigns/{campaignId}/contacts/import")
    public ResponseEntity<Map<String, Object>> importContacts(
            @PathVariable UUID campaignId,
            @RequestBody Map<String, Object> body) {
        UUID tenantId = UUID.fromString(body.get("tenant_id").toString());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> contacts = (List<Map<String, Object>>) body.get("contacts");
        int imported = contactService.importContacts(campaignId, tenantId, contacts);
        return ResponseEntity.ok(Map.of("imported", imported, "total_submitted", contacts.size()));
    }

    @GetMapping("/campaigns/{campaignId}/contacts")
    public ResponseEntity<Page<CampaignContact>> listContacts(
            @PathVariable UUID campaignId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        if (status != null && !status.isBlank()) {
            ContactStatus cs = ContactStatus.valueOf(status.toUpperCase());
            return ResponseEntity.ok(contactService.getContactsByStatus(campaignId, cs, PageRequest.of(page, size)));
        }
        return ResponseEntity.ok(contactService.getContacts(campaignId, PageRequest.of(page, size)));
    }

    @GetMapping("/campaigns/{campaignId}/contacts/stats")
    public ResponseEntity<Map<String, Long>> contactStats(@PathVariable UUID campaignId) {
        return ResponseEntity.ok(contactService.getContactStats(campaignId));
    }

    // ── FILE UPLOAD (CSV / Excel) ──

    /**
     * Upload contacts from CSV or Excel file.
     * Expected columns: phone_number (required), name, email, company, priority, + any custom fields.
     * Content-Type: multipart/form-data
     */
    @PostMapping(value = "/campaigns/{campaignId}/contacts/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> uploadContacts(
            @PathVariable UUID campaignId,
            @RequestParam UUID tenant_id,
            @RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "File is empty"));
        }
        String filename = file.getOriginalFilename() != null ? file.getOriginalFilename().toLowerCase() : "";
        try {
            Map<String, Object> result;
            if (filename.endsWith(".csv") || filename.endsWith(".tsv")) {
                result = contactService.importFromCsv(campaignId, tenant_id, file.getInputStream());
            } else if (filename.endsWith(".xlsx") || filename.endsWith(".xls")) {
                result = contactService.importFromExcel(campaignId, tenant_id, file.getInputStream());
            } else {
                return ResponseEntity.badRequest().body(Map.of("error", "Unsupported file type. Use .csv, .tsv, .xlsx, or .xls"));
            }
            log.info("Contact upload: campaign={} file={} result={}", campaignId, filename, result);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Contact upload failed: campaign={} file={} err={}", campaignId, filename, e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("error", "Upload failed: " + e.getMessage()));
        }
    }

    // ── EXPORT (CSV download) ──

    /**
     * Export campaign contacts as CSV.
     * Optional status filter: ?status=QUALIFIED
     */
    @GetMapping(value = "/campaigns/{campaignId}/contacts/export", produces = "text/csv")
    public ResponseEntity<byte[]> exportContacts(
            @PathVariable UUID campaignId,
            @RequestParam(required = false) String status) {
        ContactStatus cs = (status != null && !status.isBlank()) ? ContactStatus.valueOf(status.toUpperCase()) : null;
        byte[] csv = contactService.exportToCsv(campaignId, cs);
        String filename = "campaign_" + campaignId + (cs != null ? "_" + cs.name().toLowerCase() : "") + "_contacts.csv";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .header(HttpHeaders.CONTENT_TYPE, "text/csv; charset=UTF-8")
                .body(csv);
    }

    // ── LEAD QUALIFICATION ──

    /**
     * Mark a contact as QUALIFIED or NOT_QUALIFIED.
     * For now this is a manual action; later it can be automated by ML scoring.
     */
    @PostMapping("/campaigns/{campaignId}/contacts/{contactId}/qualify")
    public ResponseEntity<Map<String, Object>> qualifyContact(
            @PathVariable UUID campaignId,
            @PathVariable UUID contactId,
            @RequestBody Map<String, Object> body) {
        boolean qualified = Boolean.TRUE.equals(body.get("qualified"));
        String notes = (String) body.get("notes");
        contactService.qualifyContact(contactId, qualified, notes);
        return ResponseEntity.ok(Map.of(
                "contact_id", contactId.toString(),
                "status", qualified ? "QUALIFIED" : "NOT_QUALIFIED"
        ));
    }

    /**
     * Assign a contact (usually QUALIFIED) to an agent for human follow-up.
     */
    @PostMapping("/campaigns/{campaignId}/contacts/{contactId}/assign")
    public ResponseEntity<Map<String, Object>> assignToAgent(
            @PathVariable UUID campaignId,
            @PathVariable UUID contactId,
            @RequestBody Map<String, String> body) {
        UUID agentId = UUID.fromString(body.get("agent_id"));
        contactService.assignToAgent(contactId, agentId);
        return ResponseEntity.ok(Map.of(
                "contact_id", contactId.toString(),
                "agent_id", agentId.toString(),
                "status", "assigned"
        ));
    }

    // ── CRM IMPORT (stub) ──

    /**
     * Import contacts from CRM. Returns dummy response for now.
     * Future: pull leads from Salesforce, HubSpot, etc.
     */
    @PostMapping("/campaigns/{campaignId}/contacts/import-crm")
    public ResponseEntity<Map<String, Object>> importFromCrm(
            @PathVariable UUID campaignId,
            @RequestBody Map<String, Object> body) {
        String provider = (String) body.getOrDefault("provider", "SALESFORCE");
        log.info("CRM import requested: campaign={} provider={} (stub)", campaignId, provider);
        return ResponseEntity.ok(Map.of(
                "imported", 0,
                "provider", provider,
                "status", "NOT_IMPLEMENTED",
                "message", "CRM integration coming soon. Use CSV/Excel upload or JSON API for now."
        ));
    }

    // ── QUALIFIED LEADS (cross-campaign) ──

    /**
     * Get qualified leads across all campaigns for a tenant.
     * Used by admin-ui "Qualified Leads" page.
     */
    @GetMapping("/contacts/qualified")
    public ResponseEntity<Page<CampaignContact>> qualifiedLeads(
            @RequestParam UUID tenant_id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(contactService.getQualifiedLeads(tenant_id, PageRequest.of(page, size)));
    }

    // ── DNC ──

    @PostMapping("/dnc")
    public ResponseEntity<DncEntry> addDnc(@RequestBody Map<String, String> body) {
        DncEntry entry = contactService.addToDnc(
                UUID.fromString(body.get("tenant_id")),
                body.get("phone_number"),
                body.get("reason"),
                body.get("source") != null ? DncSource.valueOf(body.get("source")) : DncSource.API,
                body.get("expires_at") != null ? Instant.parse(body.get("expires_at")) : null
        );
        return ResponseEntity.ok(entry);
    }

    @DeleteMapping("/dnc/{tenantId}/{phoneNumber}")
    public ResponseEntity<Void> removeDnc(@PathVariable UUID tenantId, @PathVariable String phoneNumber) {
        contactService.removeFromDnc(tenantId, phoneNumber);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/dnc")
    public ResponseEntity<Page<DncEntry>> listDnc(
            @RequestParam UUID tenant_id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(contactService.getDncList(tenant_id, PageRequest.of(page, size)));
    }
}