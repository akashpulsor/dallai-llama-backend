package com.dalai.llama.chat.service.client;

import com.dalai.llama.chat.service.ChatException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.UUID;

@Component
public class CreativePlanningServiceClient {

    public record ReviseMarketingPlanRequest(String instructions) {
    }

    public record BrandPlanExportView(String bucket, String objectKey, String signedUrl) {
    }

    /** Mirrors only the top-level field chat-service actually reads from creative-planning-
     * service's {@code MarketingPlanGenerationResultView} -- {@code plan}/{@code findings}
     * content isn't needed for the chat summary, so they're deliberately not modeled (Spring's
     * default Jackson config ignores unknown properties, so this is safe against the fuller
     * response body). */
    public record ReviseMarketingPlanResponse(UUID critiqueSessionId) {
    }

    private final WebClient webClient;
    private final int timeoutMs;

    public CreativePlanningServiceClient(
            WebClient.Builder webClientBuilder,
            @Value("${chat.creative-planning.base-url}") String baseUrl,
            @Value("${chat.creative-planning.timeout-ms}") int timeoutMs
    ) {
        this.webClient = webClientBuilder.baseUrl(baseUrl).build();
        this.timeoutMs = timeoutMs;
    }

    public BrandPlanExportView exportMarketingPlanPdf(String tenantId, UUID planId) {
        try {
            return webClient.post()
                    .uri("/api/v1/internal/tenants/{tenantId}/marketing-plans/{planId}/export-pdf", tenantId, planId)
                    .retrieve()
                    .bodyToMono(BrandPlanExportView.class)
                    .block(Duration.ofMillis(timeoutMs));
        } catch (WebClientResponseException ex) {
            throw ChatException.upstream(
                    "creative-planning-service export-pdf failed status=%s body=%s"
                            .formatted(ex.getStatusCode(), ex.getResponseBodyAsString()));
        }
    }

    public ReviseMarketingPlanResponse reviseMarketingPlan(String tenantId, UUID planId, ReviseMarketingPlanRequest request) {
        try {
            return webClient.post()
                    .uri("/api/v1/internal/tenants/{tenantId}/marketing-plans/{planId}/revise", tenantId, planId)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(ReviseMarketingPlanResponse.class)
                    .block(Duration.ofMillis(timeoutMs));
        } catch (WebClientResponseException ex) {
            throw ChatException.upstream(
                    "creative-planning-service revise failed status=%s body=%s"
                            .formatted(ex.getStatusCode(), ex.getResponseBodyAsString()));
        }
    }
}
