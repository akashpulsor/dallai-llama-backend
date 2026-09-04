package com.dalai.llama.preprod.service.videogen;

import com.dalai.llama.preprod.service.PreProductionException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;

/** Sole client of video-generation-service's real {@code POST /v1/shots/generate} -- pre-production
 * never calls a provider or llm-gateway directly for shot dispatch, only assembles the
 * ShotContext and hands it off. Also reads the project's latest assembled final video for the
 * public review page's final-video section (see PublicProjectController). */
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

    /** Reads the project's most recent final assembly from video-generation-service's
     * {@code InternalFinalRenderController}. Returns an empty Optional cleanly when the project
     * has never been assembled, or when the assembly is not yet COMPLETED (video-gen returns
     * null fields in that case). A network/5xx failure throws {@link PreProductionException}
     * rather than silently returning empty, so a real integration bug doesn't look like "no
     * final video yet" to the caller. */
    public Optional<LatestFinalVideoView> getLatestFinalVideo(UUID tenantId, UUID projectId) {
        try {
            LatestFinalVideoView view = webClient.get()
                    .uri("/api/v1/internal/tenants/{tenantId}/projects/{projectId}/final-video/latest", tenantId, projectId)
                    .retrieve()
                    .bodyToMono(LatestFinalVideoView.class)
                    .block(Duration.ofMillis(timeoutMs));
            if (view == null || view.renderId() == null) {
                return Optional.empty();
            }
            return Optional.of(view);
        } catch (WebClientResponseException.NotFound ex) {
            return Optional.empty();
        } catch (WebClientResponseException ex) {
            throw PreProductionException.upstream(
                    "video-generation-service final-video/latest failed status=%s body=%s"
                            .formatted(ex.getStatusCode(), ex.getResponseBodyAsString()));
        }
    }

    /** Wire-mirror of video-generation-service's {@code InternalFinalRenderController.LatestFinalVideoView}
     * -- structural copy, no shared module, matches by field name via Jackson. */
    public record LatestFinalVideoView(
            UUID renderId,
            String status,
            String videoUrl,
            java.time.OffsetDateTime completedAt
    ) {}
}
