package com.dalai.llama.preprod.service.creativeplanning;

import com.dalai.llama.preprod.domain.ReferenceMediaType;
import com.dalai.llama.preprod.service.PreProductionException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/** pre-production-service -> creative-planning-service: the project's creative inputs (locked
 * idea, brief, client references) for the Creative Direction stage, and starting the client's next
 * brief once they have locked a video. Thin caller of creative-planning's internal endpoints, same
 * convention as the billing client. */
@Component
public class CreativePlanningClient {

    public record NextBrief(String shareToken) {}

    /** Structural mirror of creative-planning-service's {@code ProjectCreativeContextView}. */
    public record CreativeContext(UUID lockedIdeaId, Idea idea, Brief brief, List<ReferenceAsset> references) {

        public record Idea(String title, String concept, String targetAudience, String campaignAngle,
                           String keyMessage, String tone) {}

        public record Brief(UUID requirementId, String briefText, String targetAudience, String campaignDirection,
                            Integer durationSeconds) {}

        public record ReferenceAsset(UUID assetId, ReferenceMediaType mediaType, String bucket, String objectKey,
                                     String signedUrl, String contentType, String originalFilename,
                                     String clientInstruction, String analysis) {}

        public List<ReferenceAsset> safeReferences() {
            return references == null ? List.of() : references;
        }
    }

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

    /** 404 from creative-planning means the project has no locked idea behind it (e.g. a
     * migrated project) -- a request error here, not an upstream outage. */
    public CreativeContext getCreativeContext(UUID tenantId, UUID projectId) {
        try {
            return webClient.get()
                    .uri("/api/v1/internal/tenants/{tenantId}/projects/{projectId}/creative-context", tenantId, projectId)
                    .retrieve()
                    .bodyToMono(CreativeContext.class)
                    .block(Duration.ofMillis(timeoutMs));
        } catch (WebClientResponseException.NotFound ex) {
            throw PreProductionException.badRequest("Project " + projectId + " has no locked idea to develop a creative direction from");
        } catch (WebClientResponseException ex) {
            throw PreProductionException.upstream(
                    "creative-planning-service call failed status=%s body=%s".formatted(ex.getStatusCode(), ex.getResponseBodyAsString()));
        }
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
