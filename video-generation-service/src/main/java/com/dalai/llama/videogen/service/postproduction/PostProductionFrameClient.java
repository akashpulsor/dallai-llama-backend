package com.dalai.llama.videogen.service.postproduction;

import com.dalai.llama.videogen.service.VideoGenException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.UUID;

/**
 * Asks post-production-service for a frame of a shot's current clip.
 *
 * <p>Post-production, not this service, because it knows which cut of a shot the film actually
 * uses -- a retimed or dubbed version may have replaced the clip this service generated, and the
 * next shot has to continue from what the audience will see. Internal route, tenant in the path:
 * this runs with no user token to forward.
 */
@Slf4j
@Component
public class PostProductionFrameClient {

    private final WebClient webClient;
    private final int timeoutMs;

    public PostProductionFrameClient(
            WebClient.Builder webClientBuilder,
            @Value("${video-gen.post-production.base-url}") String baseUrl,
            @Value("${video-gen.post-production.timeout-ms}") int timeoutMs) {
        this.webClient = webClientBuilder.baseUrl(baseUrl).build();
        this.timeoutMs = timeoutMs;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Frame(UUID shotId, String bucket, String objectKey, Long frameNumber, Long timestampMs) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Extraction(java.util.List<Frame> frames) {
    }

    /** The last frame of the shot's current clip. A shot with no clip is a 409 the creator can act
     * on (generate that shot first), not an outage. */
    public Frame lastFrame(UUID tenantId, UUID projectId, UUID shotId) {
        try {
            Extraction extraction = webClient.post()
                    .uri(builder -> builder
                            .path("/api/v1/internal/tenants/{tenantId}/projects/{projectId}/shots/{shotId}/frames")
                            .queryParam("mode", "LAST_FRAME")
                            .build(tenantId, projectId, shotId))
                    .retrieve()
                    .bodyToMono(Extraction.class)
                    .block(Duration.ofMillis(timeoutMs));
            Frame frame = extraction == null || extraction.frames() == null || extraction.frames().isEmpty()
                    ? null : extraction.frames().get(0);
            if (frame == null || frame.objectKey() == null) {
                throw VideoGenException.upstream("post-production-service returned no frame for shot " + shotId);
            }
            return frame;
        } catch (WebClientResponseException ex) {
            if (ex.getStatusCode().value() == HttpStatus.CONFLICT.value()
                    || ex.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) {
                throw VideoGenException.conflict(
                        "The previous shot has no video yet, so there is no last frame to continue from. Generate it first.");
            }
            log.warn("Last-frame extraction failed shotId={} status={} body={}",
                    shotId, ex.getStatusCode(), ex.getResponseBodyAsString());
            throw VideoGenException.upstream("Could not take the previous shot's last frame -- try again.");
        } catch (VideoGenException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw VideoGenException.upstream("Could not take the previous shot's last frame -- try again. (" + ex.getMessage() + ")");
        }
    }
}
