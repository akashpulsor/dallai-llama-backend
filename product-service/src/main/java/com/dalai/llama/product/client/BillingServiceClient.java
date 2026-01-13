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
     * Record DID rental charge (monthly or initial)
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
            log.error("Failed to record DID rental for tenant {}: {}", tenantId, e.getMessage());
            throw new RuntimeException("Failed to record DID rental", e);
        }
    }

    /**
     * Record DID setup fee (one-time charge)
     */
    public void recordDidSetupFee(UUID tenantId, UUID didId, String didNumber, BigDecimal amount) {
        try {
            client().post()
                    .uri("/api/v1/internal/tenants/{tenantId}/usage", tenantId)
                    .bodyValue(Map.of(
                            "metric", "DID_SETUP",
                            "quantity", BigDecimal.ONE,
                            "unit", "UNIT",
                            "unitCost", amount,
                            "totalCost", amount,
                            "sourceType", "DID",
                            "sourceId", didId,
                            "description", "DID setup fee: " + didNumber
                    ))
                    .retrieve()
                    .toBodilessEntity()
                    .block();
            log.info("Recorded DID setup fee for tenant {}: {} @ {}", tenantId, didNumber, amount);
        } catch (Exception e) {
            log.error("Failed to record DID setup fee: {}", e.getMessage());
            throw new RuntimeException("Failed to record DID setup fee", e);
        }
    }

    /**
     * Check if tenant has sufficient balance
     */
    public boolean hasSufficientBalance(UUID tenantId, BigDecimal required) {
        try {
            CallAuthResponse resp = client().get()
                    .uri("/api/v1/internal/tenants/{tenantId}/authorize-call", tenantId)
                    .retrieve()
                    .bodyToMono(CallAuthResponse.class)
                    .block();
            return resp != null && resp.remainingBalance() != null
                    && resp.remainingBalance().compareTo(required) >= 0;
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
            BillingStateResponse resp = client().get()
                    .uri("/api/v1/internal/tenants/{tenantId}/billing-state", tenantId)
                    .retrieve()
                    .bodyToMono(BillingStateResponse.class)
                    .block();
            return resp != null ? resp.state() : "UNKNOWN";
        } catch (Exception e) {
            log.error("Failed to get billing state for tenant {}: {}", tenantId, e.getMessage());
            return "UNKNOWN";
        }
    }

    /**
     * Check if calls are allowed (ACTIVE or GRACE state)
     */
    public boolean canMakeCalls(UUID tenantId) {
        try {
            BillingStateResponse resp = client().get()
                    .uri("/api/v1/internal/tenants/{tenantId}/billing-state", tenantId)
                    .retrieve()
                    .bodyToMono(BillingStateResponse.class)
                    .block();
            return resp != null && resp.canMakeCalls();
        } catch (Exception e) {
            log.error("Failed to check call permission: {}", e.getMessage());
            return false;
        }
    }

    // Response DTOs
    public record BillingStateResponse(String state, boolean canMakeCalls) {}
    public record CallAuthResponse(boolean authorized, String state, String reason, BigDecimal remainingBalance) {}
}
