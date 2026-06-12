package com.dalai.llama.creator.service;

import com.dalai.llama.creator.config.CreatorProperties;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

@Service
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

    public record WalletBalanceResponse(BigDecimal balance, String currency) {
    }

    public static class WalletBalanceCheckException extends RuntimeException {
        public WalletBalanceCheckException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
