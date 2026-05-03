package com.dalai.llama.product.client;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Client for Billing Service.
 *
 * Base path: /api/v1/internal/tenants/{tenantId}/...
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BillingServiceClient {

    private final WebClient.Builder webClientBuilder;

    @Value("${services.billing.url:http://billing-service:8083}")
    private String billingServiceUrl;

    private WebClient client() {
        return WebClient.create(billingServiceUrl);
    }

    // ==================== WALLET ====================

    public BigDecimal getWalletBalance(UUID tenantId) {
        try {
            WalletBalanceResponse resp = client().get()
                    .uri("/api/v1/internal/tenants/{tenantId}/wallet/balance", tenantId)
                    .retrieve()
                    .bodyToMono(WalletBalanceResponse.class)
                    .block();
            return resp != null ? resp.balance() : BigDecimal.ZERO;
        } catch (Exception e) {
            log.error("Failed to get wallet balance for tenant {}: {}", tenantId, e.getMessage());
            return BigDecimal.ZERO;
        }
    }

    public WalletBalanceResponse getCurrentBalance(UUID tenantId) {
        try {
            WalletBalanceResponse resp = client().get()
                    .uri("/api/v1/internal/tenants/{tenantId}/wallet/balance", tenantId)
                    .retrieve()
                    .bodyToMono(WalletBalanceResponse.class)
                    .block();
            return resp != null ? resp : new WalletBalanceResponse( BigDecimal.ZERO, "INR");
        } catch (Exception e) {
            log.error("Failed to get wallet balance for tenant {}: {}", tenantId, e.getMessage());
            return new WalletBalanceResponse(BigDecimal.ZERO, "INR");
        }
    }

    public boolean hasSufficientBalance(UUID tenantId, BigDecimal required) {
        BigDecimal balance = getWalletBalance(tenantId);
        return balance.compareTo(required) >= 0;
    }

    // ==================== SUBSCRIPTION PAYMENT ====================

    public SubscriptionPaymentResponse createSubscriptionPayment(
            UUID tenantId,
            String planCode,
            BigDecimal planAmount,
            BigDecimal walletCredit,
            UUID subscriptionId
    ) {
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("planCode", planCode);
            body.put("planAmount", planAmount);
            body.put("walletCredit", walletCredit);
            body.put("subscriptionId", subscriptionId);

            SubscriptionPaymentResponse response = client().post()
                    .uri("/api/v1/internal/tenants/{tenantId}/subscription-payment", tenantId)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(SubscriptionPaymentResponse.class)
                    .block();

            if (response == null) {
                throw new RuntimeException("Null response from billing service");
            }

            log.info("Created subscription payment for tenant {} subscription {} amount ₹{}",
                    tenantId, subscriptionId, response.totalAmount());

            return response;

        } catch (WebClientResponseException e) {
            log.error("Failed to create subscription payment: {} - {}",
                    e.getStatusCode(), e.getResponseBodyAsString());
            throw new RuntimeException("Failed to create subscription payment", e);
        } catch (Exception e) {
            log.error("Unexpected error creating subscription payment: {}", e.getMessage());
            throw new RuntimeException("Failed to create subscription payment", e);
        }
    }

    public void chargeSubscription(UUID tenantId, UUID subscriptionId, BigDecimal amount, String planCode, String didNumber) {
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("amount", amount);
            body.put("type", "SUBSCRIPTION");
            body.put("description", String.format("Plan: %s, DID: %s", planCode, didNumber));
            body.put("subscriptionId", subscriptionId.toString());

            ChargeResponse resp = client().post()
                    .uri("/api/v1/internal/tenants/{tenantId}/wallet/charge", tenantId)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(ChargeResponse.class)
                    .block();

            if (resp != null && !resp.success()) {
                throw new RuntimeException("Charge failed: " + resp.message());
            }

            log.info("Charged subscription {} for tenant {}: ₹{}", subscriptionId, tenantId, amount);
        } catch (WebClientResponseException e) {
            log.error("Failed to charge subscription: {} - {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new RuntimeException("Failed to charge subscription", e);
        }
    }

    // ==================== RECURRING CHARGES ====================

    public void createRecurringCharge(UUID tenantId, RecurringChargeRequest request) {
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("type", request.type());
            body.put("amount", request.amount());
            body.put("frequency", request.frequency() != null ? request.frequency() : "MONTHLY");
            body.put("sourceType", request.sourceType() != null ? request.sourceType() : "");
            body.put("sourceId", request.sourceId() != null ? request.sourceId().toString() : "");
            body.put("description", request.description() != null ? request.description() : "");

            // Add subscriptionId if present
            if (request.subscriptionId() != null) {
                body.put("subscriptionId", request.subscriptionId().toString());
            }

            client().post()
                    .uri("/api/v1/internal/tenants/{tenantId}/recurring-charges", tenantId)
                    .bodyValue(body)
                    .retrieve()
                    .toBodilessEntity()
                    .block();

            log.info("Created recurring charge for tenant {}: {} @ ₹{}/{} (subscription: {})",
                    tenantId, request.type(), request.amount(), request.frequency(), request.subscriptionId());
        } catch (Exception e) {
            log.error("Failed to create recurring charge for tenant {}: {}", tenantId, e.getMessage());
        }
    }

    // ==================== BILLING STATE ====================

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

    // ==================== DID RENTAL ====================

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
            log.info("Recorded DID rental for tenant {}: {} @ ₹{}", tenantId, didNumber, amount);
        } catch (Exception e) {
            log.error("Failed to record DID rental: {}", e.getMessage());
            throw new RuntimeException("Failed to record DID rental", e);
        }
    }

    public record SubscriptionPaymentResponse(
            UUID paymentId,
            String gatewayOrderId,
            BigDecimal totalAmount,
            BigDecimal planAmount,
            BigDecimal walletCredit,
            String currency
    ) {}

    // ==================== RESPONSE DTOs ====================

    public record WalletBalanceResponse(BigDecimal balance, String currency) {}
    public record ChargeResponse(boolean success, BigDecimal newBalance, String message) {}
    public record BillingStateResponse(String state, boolean canMakeCalls) {}

    // ==================== CANCEL RECURRING CHARGES ====================

    public void cancelRecurringCharges(UUID tenantId, UUID subscriptionId) {
        try {
            client().delete()
                    .uri("/api/v1/internal/tenants/{tenantId}/recurring-charges/subscription/{subscriptionId}",
                            tenantId, subscriptionId)
                    .retrieve()
                    .toBodilessEntity()
                    .block();
            log.info("Cancelled recurring charges for tenant {} subscription {}", tenantId, subscriptionId);
        } catch (Exception e) {
            log.error("Failed to cancel recurring charges for tenant {} subscription {}: {}",
                    tenantId, subscriptionId, e.getMessage());
        }
    }

    // ==================== REQUEST DTOs ====================

    public record RecurringChargeRequest(
            String type,
            BigDecimal amount,
            String frequency,
            String sourceType,
            UUID sourceId,
            UUID subscriptionId,
            String description
    ) {
        // Without subscriptionId (backward compatible)
        public static RecurringChargeRequest platformFee(BigDecimal amount) {
            return new RecurringChargeRequest("PLATFORM_FEE", amount, "MONTHLY", null, null, null, "Monthly platform fee");
        }

        public static RecurringChargeRequest didRental(BigDecimal amount, UUID didId, String didNumber) {
            return new RecurringChargeRequest("DID_RENTAL", amount, "MONTHLY", "DID", didId, null, "DID rental: " + didNumber);
        }

        public static RecurringChargeRequest agentFee(BigDecimal amount, int agentCount) {
            return new RecurringChargeRequest("AGENT_FEE", amount, "MONTHLY", null, null, null, agentCount + " agent seats");
        }

        // With subscriptionId
        public static RecurringChargeRequest platformFee(BigDecimal amount, UUID subscriptionId) {
            return new RecurringChargeRequest("PLATFORM_FEE", amount, "MONTHLY", null, null, subscriptionId, "Monthly platform fee");
        }

        public static RecurringChargeRequest didRental(BigDecimal amount, UUID didId, String didNumber, UUID subscriptionId) {
            return new RecurringChargeRequest("DID_RENTAL", amount, "MONTHLY", "DID", didId, subscriptionId, "DID rental: " + didNumber);
        }

        public static RecurringChargeRequest agentFee(BigDecimal amount, int agentCount, UUID subscriptionId) {
            return new RecurringChargeRequest("AGENT_FEE", amount, "MONTHLY", null, null, subscriptionId, agentCount + " agent seats");
        }
    }
}