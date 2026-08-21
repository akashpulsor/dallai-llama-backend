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
            return webClient.get()
                    .uri("/v1/projects/{projectId}/jobs", projectId)
                    .header("X-Tenant-ID", tenantId.toString())
                    .retrieve()
                    .bodyToFlux(VideoGenShotJob.class)
                    .collectList()
                    .block(Duration.ofMillis(timeoutMs));
        } catch (WebClientResponseException ex) {
            throw PostProductionException.upstream(
                    "video-generation-service /v1/projects/%s/jobs failed status=%s body=%s"
                            .formatted(projectId, ex.getStatusCode(), ex.getResponseBodyAsString()));
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
                            .formatted(videoGenJobId, ex.getStatusCode(), ex.getResponseBodyAsString()));
        }
    }
}
