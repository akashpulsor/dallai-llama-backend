package com.dalai.llama.postprod.service.preproduction;

import com.dalai.llama.postprod.service.PostProductionException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.UUID;

/**
 * Real HTTP call against pre-production-service's contract -- that service does not exist in the
 * cluster yet (a separate, dedicated service with its own DB, per explicit direction: this is NOT
 * creator-service and creator-service is not to be touched for this). Until it's stood up, every
 * call here fails with a connection error, which DialogueSyncCoordinator surfaces as a normal
 * dispatch failure -- same shape as any other unreachable dependency, nothing masked.
 *
 * <p>Contract (subject to pre-production-service's own finalization): GET
 * /v1/projects/{projectId}/shots/{shotRef}/dialogue, with scriptId as an optional query param for
 * services that key by it instead of/in addition to project_id.
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
    public PreProductionShotDetails getShotDialogue(UUID projectId, UUID scriptId, String shotRef) {
        try {
            return webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/v1/projects/{projectId}/shots/{shotRef}/dialogue")
                            .queryParamIfPresent("scriptId", java.util.Optional.ofNullable(scriptId))
                            .build(projectId, shotRef))
                    .retrieve()
                    .bodyToMono(PreProductionShotDetails.class)
                    .block(Duration.ofMillis(timeoutMs));
        } catch (WebClientResponseException ex) {
            throw PostProductionException.upstream(
                    "pre-production-service /v1/projects/%s/shots/%s/dialogue failed status=%s body=%s"
                            .formatted(projectId, shotRef, ex.getStatusCode(), ex.getResponseBodyAsString()));
        } catch (RuntimeException ex) {
            throw PostProductionException.upstream(
                    "pre-production-service unreachable for project_id=%s shot_ref=%s: %s"
                            .formatted(projectId, shotRef, ex.getMessage()));
        }
    }
}
