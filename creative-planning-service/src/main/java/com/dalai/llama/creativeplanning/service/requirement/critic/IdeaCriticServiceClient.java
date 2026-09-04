package com.dalai.llama.creativeplanning.service.requirement.critic;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.UUID;

/** Scores a batch of not-yet-persisted idea candidates via critic-service's {@code
 * IdeaCritiqueService} -- same {@code creative-planning.critic.*} config {@code
 * MarketingPlanCriticServiceClient} already uses, since both talk to the same critic-service.
 * <p>
 * Unlike the marketing-plan harness (a mandatory pre-flight gate, no bypass), this one is
 * best-effort: an idea a creator hasn't picked yet is cheap to regenerate or simply ignore, so a
 * critic-service outage degrades to "no scores shown" rather than blocking idea generation
 * entirely -- see {@code ProjectRequirementIdeaService}, which catches everything this throws. */
@Slf4j
@Component
public class IdeaCriticServiceClient {

    private final WebClient webClient;
    private final int timeoutMs;

    public IdeaCriticServiceClient(
            WebClient.Builder webClientBuilder,
            @Value("${creative-planning.critic.base-url}") String baseUrl,
            @Value("${creative-planning.critic.timeout-ms}") int timeoutMs
    ) {
        this.webClient = webClientBuilder.baseUrl(baseUrl).build();
        this.timeoutMs = timeoutMs;
    }

    public IdeaCritiqueResult critique(UUID tenantId, IdeaCritiqueRequest request) {
        try {
            IdeaCritiqueResult result = webClient.post()
                    .uri("/api/v1/internal/tenants/{tenantId}/idea-critiques", tenantId)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(IdeaCritiqueResult.class)
                    .block(Duration.ofMillis(timeoutMs));
            return result == null ? new IdeaCritiqueResult(null, java.util.List.of()) : result;
        } catch (WebClientResponseException ex) {
            log.warn("critic-service idea-critiques failed status={} body={}", ex.getStatusCode(), ex.getResponseBodyAsString());
            return new IdeaCritiqueResult(null, java.util.List.of());
        } catch (Exception ex) {
            log.warn("critic-service is unreachable for idea critique: {}", ex.getMessage());
            return new IdeaCritiqueResult(null, java.util.List.of());
        }
    }
}
