package com.dalai.llama.tenant.service.client;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class DidwwClient {

    private final WebClient webClient;

    public void configureTrunk(UUID tenantId, String ip) {
        webClient.post()
                .uri("/trunks")
                .bodyValue(
                        Map.of("tenantId", tenantId, "ip", ip)
                )
                .retrieve()
                .toBodilessEntity()
                .block();
    }
}
