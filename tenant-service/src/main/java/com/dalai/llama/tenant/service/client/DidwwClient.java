package com.dalai.llama.tenant.service.client;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;
import java.util.UUID;

@Component

public class DidwwClient {

    private final WebClient.Builder webClientBuilder;
    private final String serviceUrl;

    public DidwwClient(
            WebClient.Builder webClientBuilder,
            @Value("${services.didww.url:http://some-service:8080}") String serviceUrl) {
        this.webClientBuilder = webClientBuilder;
        this.serviceUrl = serviceUrl;
    }

    private WebClient client() {
        return webClientBuilder.baseUrl(serviceUrl).build();
    }
    public void configureTrunk(UUID tenantId, String ip) {
        client().post()
                .uri("/trunks")
                .bodyValue(
                        Map.of("tenantId", tenantId, "ip", ip)
                )
                .retrieve()
                .toBodilessEntity()
                .block();
    }
}
