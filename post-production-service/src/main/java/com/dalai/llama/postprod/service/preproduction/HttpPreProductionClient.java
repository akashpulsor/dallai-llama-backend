package com.dalai.llama.postprod.service.preproduction;

import com.dalai.llama.postprod.service.PostProductionException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.UUID;

/**
 * Real HTTP call against pre-production-service's now-real {@code DialogueController} --
 * pre-production-service is a separate, dedicated service with its own DB, per explicit direction
 * (this is NOT creator-service and creator-service is not to be touched for this).
 *
 * <p>Every call here is on {@code /api/v1/internal/**}, and none carries an X-Tenant-ID header.
 * Tenant comes from the path instead, because the internal chain is permitAll and there is no JWT
 * to derive it from -- which is the whole reason it is reachable from a background thread at all.
 */
@Component
public class HttpPreProductionClient implements PreProductionClient {

    private final WebClient webClient;
    private final int timeoutMs;

    public HttpPreProductionClient(
            WebClient.Builder webClientBuilder,
            @Value("${post-production.pre-production-service.base-url}") String baseUrl,
            @Value("${post-production.pre-production-service.timeout-ms}") int timeoutMs
    ) {
        this.webClient = webClientBuilder.baseUrl(baseUrl).build();
        this.timeoutMs = timeoutMs;
    }

    @Override
    public java.util.List<PreProductionShotSummary> listShots(UUID tenantId, UUID projectId) {
        try {
            return webClient.get()
                    .uri("/api/v1/internal/tenants/{tenantId}/projects/{projectId}/shots", tenantId, projectId)
                    .retrieve()
                    .bodyToFlux(PreProductionShotSummary.class)
                    .collectList()
                    .block(Duration.ofMillis(timeoutMs));
        } catch (WebClientResponseException ex) {
            throw PostProductionException.upstream(
                    "pre-production-service shots for project %s failed status=%s body=%s"
                            .formatted(projectId, ex.getStatusCode(), ex.getResponseBodyAsString()), ex);
        }
    }

    @Override
    public void markReadyForReview(UUID tenantId, UUID projectId) {
        try {
            webClient.post()
                    .uri("/api/v1/internal/tenants/{tenantId}/projects/{projectId}/ready-for-review",
                            tenantId, projectId)
                    .retrieve()
                    .toBodilessEntity()
                    .block(Duration.ofMillis(timeoutMs));
        } catch (WebClientResponseException ex) {
            throw PostProductionException.upstream(
                    "pre-production-service ready-for-review for project %s failed status=%s body=%s"
                            .formatted(projectId, ex.getStatusCode(), ex.getResponseBodyAsString()), ex);
        }
    }

    @Override
    public String getAspectRatio(UUID tenantId, UUID projectId) {
        try {
            ProjectConfigAspect config = webClient.get()
                    .uri("/api/v1/internal/tenants/{tenantId}/projects/{projectId}/config", tenantId, projectId)
                    .retrieve()
                    .bodyToMono(ProjectConfigAspect.class)
                    .block(Duration.ofMillis(timeoutMs));
            return config == null ? null : config.aspectRatio();
        } catch (WebClientResponseException ex) {
            // A project with no config yet is a normal state, not a failure -- the caller falls back
            // to the shots' own dimensions, which is better than refusing to assemble.
            if (ex.getStatusCode().value() == 404) {
                return null;
            }
            throw PostProductionException.upstream(
                    "pre-production-service config for project %s failed status=%s body=%s"
                            .formatted(projectId, ex.getStatusCode(), ex.getResponseBodyAsString()), ex);
        }
    }

    /** Only the one field of the project config this service reads. */
    private record ProjectConfigAspect(String aspectRatio) {
    }

    @Override
    public PreProductionShotDetails getShotDialogue(UUID tenantId, UUID projectId, String shotRef) {
        try {
            // On /api/v1/internal/**. The creator-facing /v1/... route serves the same thing and
            // has no browser caller at all -- its own javadoc names this pipeline as who it is for --
            // but /v1/** requires a JWT, and this runs on a background thread with no user token, so
            // it answered 401 and the dub failed on its first call. Third of the same mistake; see
            // listJobsForProject in HttpVideoGenerationClient for the two before it.
            //
            // scriptId is no longer sent. The old route accepted it and threw it away: the service
            // method takes no such argument, and the only caller passes null.
            return webClient.get()
                    .uri("/api/v1/internal/tenants/{tenantId}/projects/{projectId}/shots/{shotRef}/dialogue",
                            tenantId, projectId, shotRef)
                    .retrieve()
                    .bodyToMono(PreProductionShotDetails.class)
                    .block(Duration.ofMillis(timeoutMs));
        } catch (WebClientResponseException ex) {
            throw PostProductionException.upstream(
                    "pre-production-service dialogue for shot %s failed status=%s body=%s"
                            .formatted(shotRef, ex.getStatusCode(), ex.getResponseBodyAsString()), ex);
        } catch (RuntimeException ex) {
            throw PostProductionException.upstream(
                    "pre-production-service unreachable for project_id=%s shot_ref=%s: %s"
                            .formatted(projectId, shotRef, ex.getMessage()), ex);
        }
    }
}
