package com.dalai.llama.creativeplanning.service.marketingplan.critic;

import com.dalai.llama.creativeplanning.service.CreativePlanningException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;

/** critic-service is the dedicated service for critic work in this system -- the marketing-plan
 * harness lives there, not here (see the package javadoc note on {@code
 * MarketingPlanGenerationService}). Mandatory pre-flight gate before a generated plan can be
 * marked FINAL, mirroring pre-production-service's own {@code CriticServiceClient}: no bypass
 * flag, the harness always runs. */
@Component
public class MarketingPlanCriticServiceClient {

    private final WebClient webClient;
    private final int timeoutMs;

    public MarketingPlanCriticServiceClient(
            WebClient.Builder webClientBuilder,
            @Value("${creative-planning.critic.base-url}") String baseUrl,
            @Value("${creative-planning.critic.timeout-ms}") int timeoutMs
    ) {
        this.webClient = webClientBuilder.baseUrl(baseUrl).build();
        this.timeoutMs = timeoutMs;
    }

    public MarketingPlanCritiqueApiResult critique(String tenantId, MarketingPlanCritiqueApiRequest request) {
        try {
            return webClient.post()
                    .uri("/api/v1/internal/tenants/{tenantId}/marketing-plan-critiques", tenantId)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(MarketingPlanCritiqueApiResult.class)
                    .block(Duration.ofMillis(timeoutMs));
        } catch (WebClientResponseException ex) {
            throw CreativePlanningException.upstream(
                    "critic-service marketing-plan-critiques failed status=%s body=%s"
                            .formatted(ex.getStatusCode(), ex.getResponseBodyAsString()));
        }
    }
}
