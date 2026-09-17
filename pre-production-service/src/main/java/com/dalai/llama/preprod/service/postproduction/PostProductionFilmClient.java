package com.dalai.llama.preprod.service.postproduction;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Where the client review page gets the finished film.
 *
 * <p>The review page has always asked another service rather than holding a video itself. It asked
 * video-generation-service until assembling moved to post-production; now it asks there, and falls
 * back to the old answer for projects assembled before the move -- see {@code PublicProjectService}.
 *
 * <p>Post-production returns only a PUBLISHED film, so the gate is enforced on its side. Nothing
 * here decides whether a client may watch something; it only asks what they may watch.
 *
 * <p>Never throws. The review page is a client-facing read and one unreachable service should degrade
 * to "no film yet", which is a state the page already renders, rather than an error page.
 */
@Slf4j
@Component
public class PostProductionFilmClient {

    private final WebClient webClient;
    private final int timeoutMs;

    public PostProductionFilmClient(
            WebClient.Builder webClientBuilder,
            @Value("${pre-production.post-production.base-url:http://post-production-service.apps.svc.cluster.local:8080}")
            String baseUrl,
            @Value("${pre-production.post-production.timeout-ms:10000}") int timeoutMs) {
        this.webClient = webClientBuilder.baseUrl(baseUrl).build();
        this.timeoutMs = timeoutMs;
    }

    public Optional<PublishedFilm> getPublishedFilm(UUID tenantId, UUID projectId) {
        try {
            PublishedFilm film = webClient.get()
                    .uri("/api/v1/internal/tenants/{tenantId}/projects/{projectId}/film/published",
                            tenantId, projectId)
                    .retrieve()
                    .bodyToMono(PublishedFilm.class)
                    .block(Duration.ofMillis(timeoutMs));
            return film != null && film.available() ? Optional.of(film) : Optional.empty();
        } catch (RuntimeException ex) {
            log.warn("Could not read the published film for project {}: {}", projectId, ex.getMessage());
            return Optional.empty();
        }
    }

    /** The shots a creator has chosen to show on their own, ahead of any film. Empty rather than
     * fatal when post-production cannot be reached -- the review page renders the film it already
     * has instead of failing outright. */
    public java.util.List<PublishedShot> getPublishedShots(UUID tenantId, UUID projectId) {
        try {
            java.util.List<PublishedShot> shots = webClient.get()
                    .uri("/api/v1/internal/tenants/{tenantId}/projects/{projectId}/clips/published",
                            tenantId, projectId)
                    .retrieve()
                    .bodyToFlux(PublishedShot.class)
                    .collectList()
                    .block(Duration.ofMillis(timeoutMs));
            return shots == null ? java.util.List.of() : shots;
        } catch (RuntimeException ex) {
            log.warn("Could not read published shots for project {}: {}", projectId, ex.getMessage());
            return java.util.List.of();
        }
    }

    /** One shot the client may watch. Mirrors post-production's ShotClipVersionView, narrowed. */
    public record PublishedShot(UUID versionId, String shotRef, int versionNumber,
                                String videoUrl, BigDecimal durationSeconds,
                                Integer width, Integer height) {
    }

    /** Wire-mirror of post-production-service's {@code InternalFilmController.PublishedFilmView}. */
    public record PublishedFilm(boolean available,
                                UUID renderId,
                                String videoUrl,
                                BigDecimal durationSeconds,
                                Integer width,
                                Integer height,
                                OffsetDateTime publishedAt,
                                OffsetDateTime completedAt) {
    }
}
