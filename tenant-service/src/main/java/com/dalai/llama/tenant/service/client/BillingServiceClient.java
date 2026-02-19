package com.dalai.llama.tenant.service.client;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.util.UUID;

@Slf4j
@Component
public class BillingServiceClient {

    private final WebClient.Builder webClientBuilder;
    private final String billingServiceUrl;

    public BillingServiceClient(
            WebClient.Builder webClientBuilder,
            @Value("${services.billing.url:http://product-service:8080}") String billingServiceUrl) {
        this.webClientBuilder = webClientBuilder;
        this.billingServiceUrl = billingServiceUrl;
    }

    private WebClient client() {
        return webClientBuilder.baseUrl(billingServiceUrl).build();
    }

    public boolean hasSufficientBalance(UUID walletId, int minBalance) {
        return client().get()
                .uri("/api/v1/internal/wallets/{id}/balance", walletId)
                .retrieve()
                .bodyToMono(Integer.class)
                .map(balance -> balance >= minBalance)
                .block();
    }

    public void createWallet(UUID tenantId) {
        client().post()
                .uri("/api/v1/internal/tenants/{tenantId}/wallet", tenantId)
                .retrieve()
                .toBodilessEntity()
                .block();
    }

    public void deleteWallet(UUID tenantId) {
        client().post()
                .uri("/api/v1/internal/tenants/{tenantId}/wallet", tenantId)
                .retrieve()
                .toBodilessEntity()
                .block();
    }

    public BigDecimal getBalance(UUID tenantId) {
        try {
            return client().get()
                    .uri("/api/v1/internal/tenants/{tenantId}/balance", tenantId)
                    .retrieve()
                    .bodyToMono(BigDecimal.class)
                    .block();
        } catch (Exception e) {
            log.error("Failed to get balance for tenant {}: {}", tenantId, e.getMessage());
            return BigDecimal.ZERO;
        }
    }
}
