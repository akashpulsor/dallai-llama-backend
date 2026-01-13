package com.dalai.llama.tenant.service.client;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class BillingServiceClient {

    private final WebClient webClient;

    public boolean hasSufficientBalance(UUID walletId, int minBalance) {
        return webClient.get()
                .uri("/api/v1/internal/wallets/{id}/balance", walletId)
                .retrieve()
                .bodyToMono(Integer.class)
                .map(balance -> balance >= minBalance)
                .block();
    }

    public void createWallet(UUID tenantId) {
        webClient.post()
                .uri("/api/v1/internal/tenants/{tenantId}/wallet", tenantId)
                .retrieve()
                .toBodilessEntity()
                .block();
    }

}
