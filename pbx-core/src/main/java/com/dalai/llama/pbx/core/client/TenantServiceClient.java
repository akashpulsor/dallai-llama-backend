package com.dalai.llama.pbx.core.client;


import com.dalai.llama.pbx.core.dto.request.ProvisionTenantUserRequest;
import com.dalai.llama.pbx.core.dto.response.ProvisionedUserResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * HTTP client to tenant-service — tier-3 fallback in the config cache chain.
 *
 * Called by TenantConfigCacheService ONLY when both Redis and DB are cache-miss.
 * This should be rare in production — most calls hit Redis (tier-1).
 *
 * Endpoints called:
 *   GET /api/v1/tenant-apps/by-did/{didNumber}         → inbound call, resolve tenant from DID
 *   GET /api/v1/tenant-apps/by-tenant/{tenantId}       → outbound call, fetch full config
 *   GET /api/v1/tenant-apps/{subscriptionId}            → subscription-based lookup
 *
 * Timeout: 10 seconds (configured in WebClientConfig).
 * On failure: returns Optional.empty() — caller decides whether to fail-open or reject.
 *
 * IMPORTANT: This client is on the call-auth hot path. If tenant-service is down,
 * TenantConfigCacheService returns empty → CallAuthorizationService rejects the call.
 * That's why cache warming (POST /api/v1/write/cache/warm) during provisioning is critical.
 * A warm cache means this HTTP call never happens during normal operation.
 */
@Slf4j
public class TenantServiceClient {

    private final WebClient client;
    private static final Duration TIMEOUT = Duration.ofSeconds(10);


    public TenantServiceClient( WebClient client) {
        this.client = client;
    }

    /**
     * Fetch TenantApp by DID number — primary inbound call lookup.
     * tenant-service must expose: GET /api/v1/tenant-apps/by-did/{didNumber}
     * Returns the full TenantApp as a Map (all 80+ fields).
     */
    @SuppressWarnings("unchecked")
    public Optional<Map<String, Object>> getTenantAppByDid(String didNumber) {
        try {
            Map<String, Object> result = client.get()
                    .uri("/api/v1/internal/tenants/apps/did/{did}", didNumber)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(TIMEOUT)
                    .block();
            log.debug("Fetched TenantApp by DID {} from tenant-service", didNumber);
            return Optional.ofNullable(result);
        } catch (WebClientResponseException.NotFound e) {
            log.warn("TenantApp not found for DID {} — tenant-service returned 404", didNumber);
            return Optional.empty();
        } catch (Exception e) {
            log.error("Failed to fetch TenantApp by DID {} from tenant-service: {}", didNumber, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Fetch TenantApp by tenantId — used for outbound calls and cache warm fallback.
     * tenant-service must expose: GET /api/v1/tenant-apps/by-tenant/{tenantId}
     */
    @SuppressWarnings("unchecked")
    public Optional<Map<String, Object>> getTenantAppByTenantId(UUID tenantId) {
        try {
            Map<String, Object> result = client.get()
                    .uri("/api/v1/internal/tenants/apps/tenant/{tenantId}", tenantId)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(TIMEOUT)
                    .block();
            log.debug("Fetched TenantApp by tenantId {} from tenant-service", tenantId);
            return Optional.ofNullable(result);
        } catch (WebClientResponseException.NotFound e) {
            log.warn("TenantApp not found for tenantId {} — tenant-service returned 404", tenantId);
            return Optional.empty();
        } catch (Exception e) {
            log.error("Failed to fetch TenantApp by tenantId {} from tenant-service: {}", tenantId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Fetch TenantApp by subscriptionId.
     * tenant-service must expose: GET /api/v1/tenant-apps/{subscriptionId}
     */
    @SuppressWarnings("unchecked")
    public Optional<Map<String, Object>> getTenantAppBySubscription(UUID subscriptionId) {
        try {
            Map<String, Object> result = client.get()
                    .uri("/api/v1/tenant-apps/{subscriptionId}", subscriptionId)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(TIMEOUT)
                    .block();
            log.debug("Fetched TenantApp by subscriptionId {} from tenant-service", subscriptionId);
            return Optional.ofNullable(result);
        } catch (WebClientResponseException.NotFound e) {
            return Optional.empty();
        } catch (Exception e) {
            log.error("Failed to fetch TenantApp by subscriptionId {}: {}", subscriptionId, e.getMessage());
            return Optional.empty();
        }
    }

    @SuppressWarnings("unchecked")
    public Optional<Map<String, Object>> provisionAgent(UUID tenantId, Map<String, Object> request) {
        try {
            Map<String, Object> result = client.post()
                    .uri("/api/v1/internal/tenants/agents/provision")
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(TIMEOUT)
                    .block();
            log.debug("Agent provision response from tenant-service for tenant {}: {}",
                    tenantId, result != null ? result.get("approved") : "null");
            return Optional.ofNullable(result);
        } catch (WebClientResponseException e) {
            log.error("Agent provisioning failed via tenant-service for {}: {} {}",
                    tenantId, e.getStatusCode(), e.getResponseBodyAsString());
            return Optional.empty();
        } catch (Exception e) {
            log.error("Agent provisioning failed for {}: {}", tenantId, e.getMessage());
            return Optional.empty();
        }
    }
    // ════════════════════════════════════════════════════════════
    // TENANT USER PROVISIONING (Credential Delivery System)
    // ════════════════════════════════════════════════════════════

    /**
     * Provision a tenant user (AGENT/SUPERVISOR) — creates KC user + TenantUser + credential delivery.
     * tenant-service endpoint: POST /api/v1/internal/tenant/users
     */
    public Optional<ProvisionedUserResult> provisionTenantUser(ProvisionTenantUserRequest request) {
        try {
            ProvisionedUserResult result = client.post()
                    .uri("/api/v1/internal/tenant/users")
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(ProvisionedUserResult.class)
                    .timeout(TIMEOUT)
                    .block();
            log.info("Provisioned tenant user via tenant-service: tenantUserId={} kcUserId={}",
                    result != null ? result.tenantUserId() : "null",
                    result != null ? result.keycloakUserId() : "null");
            return Optional.ofNullable(result);
        } catch (WebClientResponseException e) {
            log.error("Tenant user provisioning failed: {} {}", e.getStatusCode(), e.getResponseBodyAsString());
            return Optional.empty();
        } catch (Exception e) {
            log.error("Tenant user provisioning failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Deprovision a tenant user — revokes credentials, disables KC user.
     * tenant-service endpoint: POST /api/v1/internal/tenant/users/{id}/deprovision
     */
    public boolean deprovisionTenantUser(UUID tenantUserId, String reason, String requesterSubject) {
        try {
            client.post()
                    .uri("/api/v1/internal/tenant/users/{id}/deprovision", tenantUserId)
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("reason", reason, "requesterSubject", requesterSubject))
                    .retrieve()
                    .bodyToMono(Void.class)
                    .timeout(TIMEOUT)
                    .block();
            log.info("Deprovisioned tenant user {} via tenant-service", tenantUserId);
            return true;
        } catch (WebClientResponseException e) {
            log.error("Tenant user deprovision failed for {}: {} {}",
                    tenantUserId, e.getStatusCode(), e.getResponseBodyAsString());
            return false;
        } catch (Exception e) {
            log.error("Tenant user deprovision failed for {}: {}", tenantUserId, e.getMessage());
            return false;
        }
    }

    /**
     * Disable a tenant user — quick disable without full deprovision.
     * tenant-service endpoint: POST /api/v1/internal/tenant/users/{id}/disable
     */
    public boolean disableTenantUser(UUID tenantUserId) {
        try {
            client.post()
                    .uri("/api/v1/internal/tenant/users/{id}/disable", tenantUserId)
                    .retrieve()
                    .bodyToMono(Void.class)
                    .timeout(TIMEOUT)
                    .block();
            log.info("Disabled tenant user {} via tenant-service", tenantUserId);
            return true;
        } catch (Exception e) {
            log.error("Tenant user disable failed for {}: {}", tenantUserId, e.getMessage());
            return false;
        }
    }

    /**
     * Health check — verify tenant-service is reachable.
     * Used by actuator health indicator.
     */
    public boolean isHealthy() {
        try {
            client.get()
                    .uri("/actuator/health")
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(3))
                    .block();
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
