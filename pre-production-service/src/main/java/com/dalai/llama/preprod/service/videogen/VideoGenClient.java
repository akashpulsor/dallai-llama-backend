package com.dalai.llama.preprod.service.videogen;

import com.dalai.llama.preprod.service.PreProductionException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;

/** Sole client of video-generation-service's real {@code POST /v1/shots/generate} -- pre-production
 * never calls a provider or llm-gateway directly for shot dispatch, only assembles the
 * ShotContext and hands it off. */
@Component
public class VideoGenClient {

    private final WebClient webClient;
    private final int timeoutMs;

    public VideoGenClient(
            WebClient.Builder webClientBuilder,
            @Value("${pre-production.video-gen.base-url}") String baseUrl,
            @Value("${pre-production.video-gen.timeout-ms}") int timeoutMs
    ) {
        this.webClient = webClientBuilder.baseUrl(baseUrl).build();
        this.timeoutMs = timeoutMs;
    }

    public GenerateShotResponse generateShot(String tenantId, GenerateShotRequest request) {
        try {
            return webClient.post()
                    .uri("/api/v1/internal/tenants/{tenantId}/shots/generate", tenantId)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(GenerateShotResponse.class)
                    .block(Duration.ofMillis(timeoutMs));
        } catch (WebClientResponseException ex) {
            throw PreProductionException.upstream(
                    "video-generation-service shots/generate failed status=%s body=%s"
                            .formatted(ex.getStatusCode(), ex.getResponseBodyAsString()));
        }
    }
}
