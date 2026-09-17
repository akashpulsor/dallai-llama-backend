package com.dalai.llama.postprod.service.videogen;

import com.dalai.llama.postprod.service.PostProductionException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
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
        // video-generation-service's GET /v1/jobs/{id}/video is a 302 redirect to a signed MinIO
        // URL -- resolved without following it, since we want the URL itself (to re-download and
        // persist our own copy), not to fetch the video through this client.
        try {
            return webClient.get()
                    .uri("/v1/jobs/{jobId}/video", videoGenJobId)
                    .header("X-Tenant-ID", tenantId.toString())
                    .exchangeToMono(response -> {
                        HttpStatusCode status = response.statusCode();
                        if (status.is3xxRedirection()) {
                            String location = response.headers().header("Location").stream().findFirst().orElse(null);
                            if (location == null || location.isBlank()) {
                                return reactor.core.publisher.Mono.error(PostProductionException.upstream(
                                        "video-generation-service /v1/jobs/%s/video redirected with no Location header".formatted(videoGenJobId)));
                            }
                            return reactor.core.publisher.Mono.just(location);
                        }
                        return response.createException().flatMap(reactor.core.publisher.Mono::error);
                    })
                    .block(Duration.ofMillis(timeoutMs));
        } catch (WebClientResponseException ex) {
            throw PostProductionException.upstream(
                    "video-generation-service /v1/jobs/%s/video failed status=%s body=%s"
                            .formatted(videoGenJobId, ex.getStatusCode(), ex.getResponseBodyAsString()), ex);
        }
    }
}
