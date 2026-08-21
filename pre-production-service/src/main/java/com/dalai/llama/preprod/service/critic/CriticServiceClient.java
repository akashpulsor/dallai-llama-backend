package com.dalai.llama.preprod.service.critic;

import com.dalai.llama.preprod.service.PreProductionException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;

/** critic-service is a mandatory pre-flight gate before every video-generation-service dispatch --
 * see {@code ShotContextAssemblyService.dispatch()}. No bypass flag: the harness always runs. */
@Component
public class CriticServiceClient {

    private final WebClient webClient;
    private final int timeoutMs;

    public CriticServiceClient(
            WebClient.Builder webClientBuilder,
            @Value("${pre-production.critic.base-url}") String baseUrl,
            @Value("${pre-production.critic.timeout-ms}") int timeoutMs
    ) {
        this.webClient = webClientBuilder.baseUrl(baseUrl).build();
        this.timeoutMs = timeoutMs;
    }

    public CritiqueResult critique(String tenantId, CritiqueRequest request) {
        try {
            return webClient.post()
                    .uri("/api/v1/internal/tenants/{tenantId}/critiques", tenantId)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(CritiqueResult.class)
                    .block(Duration.ofMillis(timeoutMs));
        } catch (WebClientResponseException ex) {
            throw PreProductionException.upstream(
                    "critic-service critiques failed status=%s body=%s".formatted(ex.getStatusCode(), ex.getResponseBodyAsString()));
        }
    }
}
