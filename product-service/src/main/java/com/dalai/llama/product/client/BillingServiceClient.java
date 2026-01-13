package com.dalai.llama.product.client;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * Client for Billing Service - called when DIDs are provisioned/released
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BillingServiceClient {

    private final WebClient.Builder webClientBuilder;

    @Value("${services.billing.url:http://localhost:8083}")
    private String billingServiceUrl;

    private WebClient client() {
        return webClientBuilder.baseUrl(billingServiceUrl).build();
    }

    /**
     * Record DID rental charge when DID is provisioned
     */
    public void recordDidRental(UUID tenantId, UUID didId, String didNumber, BigDecimal amount) {
        try {
            client().post()
                    .uri("/api/v1/internal/tenants/{tenantId}/did-rental", tenantId)
                    .bodyValue(Map.of(
                            "didId", didId,
                            "didNumber", didNumber,
                            "amount", amount
                    ))
                    .retrieve()
                    .toBodilessEntity()
                    .block();
            log.info("Recorded DID rental for tenant {}: {} @ {}", tenantId, didNumber, amount);
        } catch (Exception e) {
            log.error("Failed to record DID rental: {}", e.getMessage());
        }
    }

    /**
     * Check if tenant has sufficient balance for DID purchase
     */
    public boolean hasSufficientBalance(UUID tenantId, BigDecimal required) {
        try {
            BigDecimal balance = client().get()
                    .uri("/api/v1/internal/tenants/{tenantId}/wallet/balance", tenantId)
                    .retrieve()
                    .bodyToMono(BigDecimal.class)
                    .block();
            return balance != null && balance.compareTo(required) >= 0;
        } catch (Exception e) {
            log.error("Failed to check balance for tenant {}: {}", tenantId, e.getMessage());
            return false;
        }
    }

    /**
     * Get billing state for tenant
     */
    public String getBillingState(UUID tenantId) {
        try {
            BillingStateInfo info = client().get()
                    .uri("/api/v1/internal/tenants/{tenantId}/billing-state", tenantId)
                    .retrieve()
                    .bodyToMono(BillingStateInfo.class)
                    .block();
            return info != null ? info.state() : "UNKNOWN";
        } catch (Exception e) {
            log.error("Failed to get billing state for tenant {}: {}", tenantId, e.getMessage());
            return "UNKNOWN";
        }
    }

    public record BillingStateInfo(String state, boolean canMakeCalls) {}
}
