package com.dalai.llama.billing.client;


import com.dalai.llama.billing.dto.response.SubscriptionResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.UUID;

/**
 * Client for Product Service - used for DID rental billing
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductServiceClient {

    private final WebClient.Builder webClientBuilder;

    @Value("${services.product.url:http://localhost:8082}")
    private String productServiceUrl;

    private WebClient client() {
        return webClientBuilder.baseUrl(productServiceUrl).build();
    }



    /**
     * Activate subscription after successful payment webhook
     */


    public SubscriptionResponse postSubscription(UUID subscriptionId) {
        try {
            SubscriptionResponse response = client().post()
                    .uri(
                            "/api/v1/internal/products/subscriptions/{subscriptionId}/activate",
                            subscriptionId
                    )
                    .retrieve()
                    .bodyToMono(SubscriptionResponse.class)
                    .block();
            log.info(
                    "Post-subscription activation successful for {} tenantAppId={}",
                    subscriptionId,
                    response != null ? response.getTenantAppId() : null
            );
            return response;
        } catch (Exception e) {
            log.error(
                    "Failed to activate subscription {}: {}",
                    subscriptionId,
                    e.getMessage(),
                    e
            );
            throw new RuntimeException(
                    "Failed to activate subscription " + subscriptionId,
                    e
            );
        }
    }
    /**
     * Get all active DIDs for a tenant (for monthly rental billing)
     */
    public List<DidInfo> getActiveDids(UUID tenantId) {
        try {
            return client().get()
                    .uri("/api/v1/internal/tenants/{tenantId}/dids/purchased", tenantId)
                    .retrieve()
                    .bodyToFlux(DidInfo.class)
                    .collectList()
                    .block();
        } catch (Exception e) {
            log.error("Failed to get active DIDs for tenant {}: {}", tenantId, e.getMessage());
            return List.of();
        }
    }

    /**
     * Get entitlements for a tenant (to check limits)
     */
    public EntitlementsInfo getEntitlements(UUID tenantId) {
        try {
            return client().get()
                    .uri("/api/v1/internal/tenants/{tenantId}/entitlements", tenantId)
                    .retrieve()
                    .bodyToMono(EntitlementsInfo.class)
                    .block();
        } catch (Exception e) {
            log.error("Failed to get entitlements for tenant {}: {}", tenantId, e.getMessage());
            return null;
        }
    }

    public record DidInfo(UUID id, String number, String country, java.math.BigDecimal monthlyRate) {}
    public record EntitlementsInfo(int maxAgents, int maxChannels, int maxDids, boolean aiEnabled) {}
}
