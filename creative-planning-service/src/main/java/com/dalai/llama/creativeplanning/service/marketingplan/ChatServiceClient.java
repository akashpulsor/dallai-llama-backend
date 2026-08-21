package com.dalai.llama.creativeplanning.service.marketingplan;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.UUID;

/**
 * Pushes a finalized marketing plan into chat-service's central embedding index so it becomes
 * chattable -- the "everything we generate is saved as embedding and chattable" requirement.
 * Best-effort by design: a chat-service outage is not a reason to fail marketing-plan generation,
 * so every call here swallows its own errors (logged, not thrown). This is the first of several
 * producers that should eventually push into chat-service -- critic-service's findings,
 * pre-production-service's shots/storyboards are not wired yet (see chat-service's own
 * InternalDocumentIngestionController javadoc).
 */
@Slf4j
@Component
class ChatServiceClient {

    private record IngestDocumentRequest(String sourceService, String sourceId, String kind, UUID scopeId, String content) {
    }

    private final WebClient webClient;
    private final int timeoutMs;

    ChatServiceClient(
            WebClient.Builder webClientBuilder,
            @Value("${creative-planning.chat.base-url}") String baseUrl,
            @Value("${creative-planning.chat.timeout-ms}") int timeoutMs
    ) {
        this.webClient = webClientBuilder.baseUrl(baseUrl).build();
        this.timeoutMs = timeoutMs;
    }

    void ingestMarketingPlan(UUID tenantId, UUID planId, String content) {
        try {
            webClient.post()
                    .uri("/api/v1/internal/tenants/{tenantId}/embedded-documents", tenantId)
                    .bodyValue(new IngestDocumentRequest("creative-planning-service", planId.toString(), "MARKETING_PLAN", planId, content))
                    .retrieve()
                    .toBodilessEntity()
                    .block(Duration.ofMillis(timeoutMs));
        } catch (WebClientResponseException ex) {
            log.warn("chat-service ingestion failed for marketing plan {} status={} body={}",
                    planId, ex.getStatusCode(), ex.getResponseBodyAsString());
        } catch (Exception ex) {
            log.warn("chat-service ingestion failed for marketing plan {}: {}", planId, ex.getMessage());
        }
    }
}
