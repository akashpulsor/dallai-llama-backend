package com.dalai.llama.pbx.core.client;



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
 * HTTP client to product-service — billing validation during call authorization.
 *
 * Called by CallAuthorizationService on the hot path:
 *   Inbound:  getSubscriptionStatus() → is subscription ACTIVE? (not SUSPENDED/CANCELLED)
 *   Outbound: checkBalance()          → does tenant have credits for outbound calls?
 *
 * Design decisions:
 *
 * 1. FAIL-OPEN: If product-service is unreachable or times out, we ALLOW the call.
 *    Rationale: Better to let a call through (and bill retroactively) than block revenue
 *    because the billing service is temporarily down. The CDR records the call regardless.
 *
 * 2. AGGRESSIVE TIMEOUT: 5 seconds max (configured in WebClientConfig).
 *    Kamailio http_client timeout is ~3-5s. We must respond before Kamailio gives up.
 *    If product-service is slow, we skip the check (fail-open) rather than delay the call.
 *
 * 3. NO RETRY on billing checks: Unlike config lookups, billing checks should not retry.
 *    A failed billing check = allow the call. Retrying would double the latency for no benefit.
 *    (WebClientConfig has retry logic, but it only triggers on 5xx — a timeout is not retried.)
 *
 * Endpoints called:
 *   GET /api/v1/subscriptions/{subscriptionId}/status    → {"status": "ACTIVE|SUSPENDED|..."}
 *   GET /api/v1/billing/tenants/{tenantId}/balance       → {"balance": 1234.50, "currency": "INR"}
 */
@Slf4j
@Component
public class ProductServiceClient {

    private final WebClient client;
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    public ProductServiceClient( WebClient client) {
        this.client = client;
    }

    /**
     * Check subscription status — is it ACTIVE?
     *
     * Called on EVERY inbound call (if subscriptionId is known).
     * Returns the status response or empty on failure.
     *
     * Expected response: {"status": "ACTIVE", "plan_code": "PRO", ...}
     * CallAuthorizationService checks: if status is SUSPENDED or CANCELLED → reject.
     */
    @SuppressWarnings("unchecked")
    public Optional<Map<String, Object>> getSubscriptionStatus(UUID subscriptionId) {
        try {
            Map<String, Object> result = client.get()
                    .uri("/api/v1/subscriptions/{id}/status", subscriptionId)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(TIMEOUT)
                    .block();
            log.debug("Subscription {} status: {}", subscriptionId,
                    result != null ? result.get("status") : "null");
            return Optional.ofNullable(result);
        } catch (WebClientResponseException.NotFound e) {
            log.warn("Subscription {} not found in product-service", subscriptionId);
            return Optional.empty();
        } catch (Exception e) {
            // FAIL-OPEN: product-service down → allow the call
            log.warn("Subscription status check failed for {} — allowing call (fail-open): {}",
                    subscriptionId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Check tenant balance — sufficient credits for outbound calling?
     *
     * Called on outbound calls only.
     * Returns the balance response or empty on failure.
     *
     * Expected response: {"balance": 1234.50, "currency": "INR", "credit_limit": 0}
     * CallAuthorizationService checks: if balance <= 0 → reject.
     */
    @SuppressWarnings("unchecked")
    public Optional<Map<String, Object>> checkBalance(UUID tenantId) {
        try {
            Map<String, Object> result = client.get()
                    .uri("/api/v1/billing/tenants/{id}/balance", tenantId)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(TIMEOUT)
                    .block();
            log.debug("Tenant {} balance: {}", tenantId,
                    result != null ? result.get("balance") : "null");
            return Optional.ofNullable(result);
        } catch (Exception e) {
            // FAIL-OPEN: billing service down → allow the call
            log.warn("Balance check failed for tenant {} — allowing call (fail-open): {}",
                    tenantId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Health check — verify product-service is reachable.
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