package com.dalai.llama.tenant.service.client;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class ProductServiceClient {

    private final WebClient webClient;

    public boolean hasPurchasedDid(UUID tenantId) {
        return webClient.get()
                .uri("/api/v1/internal/tenants/{id}/dids", tenantId)
                .retrieve()
                .bodyToMono(Boolean.class)
                .block();
    }

    public boolean assignDefaultPlan(UUID tenantId, String productCode) {
        return webClient.get()
                .uri("/api/v1/internal/tenants/{id}/default-plan", tenantId)
                .retrieve()
                .bodyToMono(Boolean.class)
                .block();
    }
}
