package com.dalai.llama.postprod.service.videogen;

import com.dalai.llama.postprod.service.PostProductionException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

@Component
public class HttpVideoGenerationClient implements VideoGenerationClient {

    private final WebClient webClient;
    private final int timeoutMs;

    public HttpVideoGenerationClient(
            WebClient.Builder webClientBuilder,
            @Value("${post-production.video-gen.base-url}") String baseUrl,
            @Value("${post-production.video-gen.timeout-ms}") int timeoutMs
    ) {
        this.webClient = webClientBuilder.baseUrl(baseUrl).build();
        this.timeoutMs = timeoutMs;
    }

    @Override
    public List<VideoGenShotJob> listJobsForProject(UUID tenantId, UUID projectId) {
        try {
            // On /api/v1/internal/**, not the creator-facing /v1/** route. Istio's RBAC refuses
            // service-to-service calls to /v1/**: this returned "403 RBAC: access denied", which the
            // caller read as "no shots are generated" -- so a project with thirteen finished shots
            // reported all thirteen missing from the film.
            return webClient.get()
                    .uri("/api/v1/internal/tenants/{tenantId}/projects/{projectId}/shot-jobs",
                            tenantId, projectId)
                    .retrieve()
                    .bodyToFlux(VideoGenShotJob.class)
                    .collectList()
                    .block(Duration.ofMillis(timeoutMs));
        } catch (WebClientResponseException ex) {
            throw PostProductionException.upstream(
                    "video-generation-service shot-jobs for project %s failed status=%s body=%s"
                            .formatted(projectId, ex.getStatusCode(), ex.getResponseBodyAsString()), ex);
        }
    }

    @Override
    public com.dalai.llama.postprod.service.clip.ShotClipSource getClipSource(
            UUID tenantId, UUID projectId, UUID shotId) {
        try {
            return webClient.get()
                    .uri("/api/v1/internal/tenants/{tenantId}/projects/{projectId}/shots/{shotId}/clip-source",
                            tenantId, projectId, shotId)
                    .retrieve()
                    .bodyToMono(com.dalai.llama.postprod.service.clip.ShotClipSource.class)
                    .block(Duration.ofMillis(timeoutMs));
        } catch (WebClientResponseException ex) {
            throw PostProductionException.upstream(
                    "video-generation-service clip-source for shot %s failed status=%s body=%s"
                            .formatted(shotId, ex.getStatusCode(), ex.getResponseBodyAsString()), ex);
        }
    }

    @Override
    public String getShotVideoUrl(UUID tenantId, UUID videoGenJobId) {
        // On the internal route, which answers the signed MinIO URL as a value. The creator-facing
        // /v1/jobs/{id}/video returns that same URL as a 302, and a browser is right to use it --
        // but it sits behind JWT auth, and this call is made from a background thread with no user
        // token, so it answered 401. Same mistake as listJobsForProject above, one layer further
        // in: /v1/jobs IS in video-generation-service's Istio apiPaths, so it cleared RBAC and was
        // refused by Spring Security instead.
        try {
            VideoUrl videoUrl = webClient.get()
                    .uri("/api/v1/internal/tenants/{tenantId}/jobs/{jobId}/video-url",
                            tenantId, videoGenJobId)
                    .retrieve()
                    .bodyToMono(VideoUrl.class)
                    .block(Duration.ofMillis(timeoutMs));
            if (videoUrl == null || videoUrl.url() == null || videoUrl.url().isBlank()) {
                throw PostProductionException.upstream(
                        "video-generation-service returned no video URL for job %s".formatted(videoGenJobId));
            }
            return videoUrl.url();
        } catch (WebClientResponseException ex) {
            throw PostProductionException.upstream(
                    "video-generation-service video-url for job %s failed status=%s body=%s"
                            .formatted(videoGenJobId, ex.getStatusCode(), ex.getResponseBodyAsString()), ex);
        }
    }

    /** The one field the internal video-url route returns. */
    private record VideoUrl(String url) {
    }
}
