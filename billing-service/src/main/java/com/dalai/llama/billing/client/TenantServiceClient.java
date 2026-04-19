package com.dalai.llama.billing.client;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.UUID;

/**
 * Client for Tenant Service - used to notify billing state changes
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
     * Get tenant info
     */
    public TenantInfo getTenant(UUID tenantId) {
        try {
            return client().get()
                    .uri("/api/v1/internal/tenants/{tenantId}", tenantId)
                    .retrieve()
                    .bodyToMono(TenantInfo.class)
                    .block();
        } catch (Exception e) {
            log.error("Failed to get tenant {}: {}", tenantId, e.getMessage());
            return null;
        }
    }

    public record TenantInfo(UUID id, String name, String status, String planCode) {}
}
