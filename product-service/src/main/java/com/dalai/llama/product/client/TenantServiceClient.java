package com.dalai.llama.product.client;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.UUID;

/**
 * Client for Tenant Service - called to notify DID purchases and plan assignments
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TenantServiceClient {

    private final WebClient.Builder webClientBuilder;

    @Value("${services.tenant.url:http://localhost:8081}")
    private String tenantServiceUrl;

    private WebClient client() {
        return webClientBuilder.baseUrl(tenantServiceUrl).build();
    }

    /**
     * Notify tenant service when DID is purchased
     */
    public void notifyDidPurchased(UUID tenantId) {
        try {
            client().put()
                    .uri("/api/v1/internal/tenants/{tenantId}/did-purchased", tenantId)
                    .retrieve()
                    .toBodilessEntity()
                    .block();
            log.info("Notified tenant service of DID purchase for tenant {}", tenantId);
        } catch (Exception e) {
            log.error("Failed to notify DID purchase: {}", e.getMessage());
        }
    }

    /**
     * Notify tenant service when plan is assigned
     */
    public void notifyPlanAssigned(UUID tenantId, UUID planId, String planCode) {
        try {
            client().put()
                    .uri(uriBuilder -> uriBuilder
                            .path("/api/v1/internal/tenants/{tenantId}/plan-assigned")
                            .queryParam("planId", planId)
                            .queryParam("planCode", planCode)
                            .build(tenantId))
                    .retrieve()
                    .toBodilessEntity()
                    .block();
            log.info("Notified tenant service of plan assignment: {} -> {}", tenantId, planCode);
        } catch (Exception e) {
            log.error("Failed to notify plan assignment: {}", e.getMessage());
        }
    }

    /**
     * Get tenant status
     */
    public TenantStatus getTenantStatus(UUID tenantId) {
        try {
            return client().get()
                    .uri("/api/v1/internal/tenants/{tenantId}/status", tenantId)
                    .retrieve()
                    .bodyToMono(TenantStatus.class)
                    .block();
        } catch (Exception e) {
            log.error("Failed to get tenant status: {}", e.getMessage());
            return null;
        }
    }

    public record TenantStatus(UUID id, String status, String billingState) {}
}
