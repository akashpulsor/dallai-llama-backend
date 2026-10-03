package com.dalai.llama.preprod.service.creativeplanning;

import com.dalai.llama.preprod.service.PreProductionException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.UUID;

/** pre-production-service -> creative-planning-service, for the one thing a review page asks of the
 * brief side: starting the client's next brief once they have locked this video. Thin caller of
 * creative-planning's internal endpoint, same convention as the billing client. */
@Component
public class CreativePlanningClient {

    public record NextBrief(String shareToken) {}

    private final WebClient webClient;
    private final int timeoutMs;

    public CreativePlanningClient(
            WebClient.Builder webClientBuilder,
            @Value("${pre-production.creative-planning.base-url}") String baseUrl,
            @Value("${pre-production.creative-planning.timeout-ms}") int timeoutMs
    ) {
        this.webClient = webClientBuilder.baseUrl(baseUrl).build();
        this.timeoutMs = timeoutMs;
    }

    public NextBrief startNextBrief(UUID tenantId, UUID projectId) {
        try {
            return webClient.post()
                    .uri("/api/v1/internal/tenants/{tenantId}/projects/{projectId}/next-brief", tenantId, projectId)
                    .retrieve()
                    .bodyToMono(NextBrief.class)
                    .block(Duration.ofMillis(timeoutMs));
        } catch (WebClientResponseException ex) {
            throw PreProductionException.upstream(
                    "creative-planning-service call failed status=%s body=%s".formatted(ex.getStatusCode(), ex.getResponseBodyAsString()));
        }
    }
}
