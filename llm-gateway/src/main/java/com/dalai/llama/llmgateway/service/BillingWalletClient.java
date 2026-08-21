package com.dalai.llama.llmgateway.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

/**
 * Reads a tenant's wallet balance from billing-service's internal API -- the same
 * {@code GET /api/v1/internal/tenants/{tenantId}/wallet/balance} endpoint
 * creator-service's {@code BillingWalletService} already uses, so the two services agree on
 * one source of truth for "can this tenant afford to spend right now."
 *
 * <p>Debiting stays on the existing {@code llm.billing.events} Kafka path (see
 * {@link com.dalai.llama.llmgateway.kafka.BillingEventPublisher}) -- wiring billing-service to
 * actually consume that topic is a billing-service-side change, out of scope here. This class
 * only ever reads.
 */
@Slf4j
@Service
public class BillingWalletClient {

    private final WebClient webClient;
    private final int timeoutMs;

    public BillingWalletClient(
            WebClient.Builder webClientBuilder,
            @Value("${llm-gateway.billing.billing-service-url}") String billingServiceUrl,
            @Value("${llm-gateway.billing.wallet-check-timeout-ms}") int timeoutMs
    ) {
        this.webClient = webClientBuilder.baseUrl(billingServiceUrl).build();
        this.timeoutMs = timeoutMs;
    }

    public BigDecimal getWalletBalance(UUID tenantId) {
        try {
            WalletBalanceResponse response = webClient.get()
                    .uri("/api/v1/internal/tenants/{tenantId}/wallet/balance", tenantId)
                    .retrieve()
                    .bodyToMono(WalletBalanceResponse.class)
                    .block(Duration.ofMillis(timeoutMs));
            return response == null || response.balance() == null ? BigDecimal.ZERO : response.balance();
        } catch (WebClientResponseException.NotFound ex) {
            return BigDecimal.ZERO;
        } catch (RuntimeException ex) {
            throw new WalletBalanceCheckException("Unable to verify wallet balance for tenantId=" + tenantId, ex);
        }
    }

    public record WalletBalanceResponse(BigDecimal balance, String currency) {
    }

    public static class WalletBalanceCheckException extends RuntimeException {
        public WalletBalanceCheckException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
