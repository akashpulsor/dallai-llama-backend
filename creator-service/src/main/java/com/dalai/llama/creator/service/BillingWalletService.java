package com.dalai.llama.creator.service;

import com.dalai.llama.creator.config.CreatorProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
@Slf4j
public class BillingWalletService {

    private final CreatorProperties properties;
    private final WebClient webClient;

    public BillingWalletService(CreatorProperties properties, WebClient.Builder webClientBuilder) {
        this.properties = properties;
        this.webClient = webClientBuilder
                .baseUrl(properties.getServices().getBillingUrl())
                .build();
    }

    public BigDecimal getWalletBalance(UUID tenantId) {
        WalletBalanceResponse response = getWalletBalanceDetails(tenantId);
        return response.balance() == null ? BigDecimal.ZERO : response.balance();
    }

    public WalletBalanceResponse getWalletBalanceDetails(UUID tenantId) {
        try {
            WalletBalanceResponse response = webClient.get()
                    .uri("/api/v1/internal/tenants/{tenantId}/wallet/balance", tenantId)
                    .retrieve()
                    .bodyToMono(WalletBalanceResponse.class)
                    .block(Duration.ofMillis(properties.getBilling().getWalletCheckTimeoutMs()));
            if (response == null || response.balance() == null) {
                return new WalletBalanceResponse(BigDecimal.ZERO, null);
            }
            return response;
        } catch (WebClientResponseException.NotFound ex) {
            return new WalletBalanceResponse(BigDecimal.ZERO, null);
        } catch (RuntimeException ex) {
            throw new WalletBalanceCheckException("Unable to verify wallet balance", ex);
        }
    }

    public void recordHumanCreativeServiceUsage(
            UUID tenantId,
            UUID sourceId,
            BigDecimal amount,
            String currency,
            String description,
            String idempotencyKey
    ) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("metric", "AI_PROVIDER_USAGE");
        request.put("quantity", BigDecimal.ONE);
        request.put("unit", "COUNT");
        request.put("unitCost", amount);
        request.put("totalCost", amount);
        request.put("sourceType", "HUMAN_CREATIVE_SERVICE");
        request.put("sourceId", sourceId);
        request.put("description", description);
        request.put("currency", currency == null || currency.isBlank() ? "INR" : currency);
        request.put("idempotencyKey", idempotencyKey);
        log.info("WALLET_DEBIT_AUDIT_REQUEST tenantId={} sourceType=HUMAN_CREATIVE_SERVICE sourceId={} amount={} currency={} idempotencyKey={} description={}",
                tenantId, sourceId, amount, request.get("currency"), idempotencyKey, description);
        try {
            webClient.post()
                    .uri("/api/v1/internal/tenants/{tenantId}/usage", tenantId)
                    .bodyValue(request)
                    .retrieve()
                    .toBodilessEntity()
                    .block(Duration.ofMillis(properties.getBilling().getWalletCheckTimeoutMs()));
        } catch (RuntimeException ex) {
            throw new WalletBalanceCheckException("Unable to debit wallet for human creative service", ex);
        }
    }

    public void recordCreatorPackageUsage(
            UUID tenantId,
            UUID sourceId,
            BigDecimal amount,
            String currency,
            String description,
            String idempotencyKey
    ) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("metric", "AI_PROVIDER_USAGE");
        request.put("quantity", BigDecimal.ONE);
        request.put("unit", "COUNT");
        request.put("unitCost", amount);
        request.put("totalCost", amount);
        request.put("sourceType", "CREATOR_VIDEO_PACKAGE");
        request.put("sourceId", sourceId);
        request.put("description", description);
        request.put("currency", currency == null || currency.isBlank() ? "INR" : currency);
        request.put("idempotencyKey", idempotencyKey);
        log.info("WALLET_DEBIT_AUDIT_REQUEST tenantId={} sourceType=CREATOR_VIDEO_PACKAGE sourceId={} amount={} currency={} idempotencyKey={} description={}",
                tenantId, sourceId, amount, request.get("currency"), idempotencyKey, description);
        try {
            webClient.post()
                    .uri("/api/v1/internal/tenants/{tenantId}/usage", tenantId)
                    .bodyValue(request)
                    .retrieve()
                    .toBodilessEntity()
                    .block(Duration.ofMillis(properties.getBilling().getWalletCheckTimeoutMs()));
        } catch (RuntimeException ex) {
            throw new WalletBalanceCheckException("Unable to debit wallet for creator package", ex);
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
