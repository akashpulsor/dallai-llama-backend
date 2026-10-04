package com.dalai.llama.videogen.service.postproduction;

import com.dalai.llama.videogen.service.VideoGenException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Asks post-production-service for the last frame of a shot's current clip.
 *
 * <p>Post-production, not this service, because it knows which cut of a shot the film actually
 * uses. It takes frames off the request thread: asking returns the frame at once when it is already
 * stored for that cut, otherwise a request id to poll with {@link #status}. Internal routes, tenant
 * in the path: this runs with no user token to forward.
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

    /** A frame request as post-production reports it: QUEUED, PROCESSING, COMPLETED or FAILED. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FrameRequest(UUID requestId, String status, String error, List<Frame> frames) {

        public boolean completed() {
            return "COMPLETED".equals(status) && frames != null && !frames.isEmpty();
        }

        public boolean failed() {
            return "FAILED".equals(status) || ("COMPLETED".equals(status) && (frames == null || frames.isEmpty()));
        }

        public Frame frame() {
            return completed() ? frames.get(0) : null;
        }
    }

    public FrameRequest requestLastFrame(UUID tenantId, UUID projectId, UUID shotId) {
        return call(() -> webClient.post()
                .uri(builder -> builder
                        .path("/api/v1/internal/tenants/{tenantId}/projects/{projectId}/shots/{shotId}/frames")
                        .queryParam("mode", "LAST_FRAME")
                        .build(tenantId, projectId, shotId))
                .retrieve()
                .bodyToMono(FrameRequest.class)
                .block(Duration.ofMillis(timeoutMs)));
    }

    public FrameRequest status(UUID tenantId, UUID requestId) {
        return call(() -> webClient.get()
                .uri("/api/v1/internal/tenants/{tenantId}/frames/requests/{requestId}", tenantId, requestId)
                .retrieve()
                .bodyToMono(FrameRequest.class)
                .block(Duration.ofMillis(timeoutMs)));
    }

    private FrameRequest call(java.util.function.Supplier<FrameRequest> request) {
        try {
            FrameRequest answer = request.get();
            if (answer == null) {
                throw VideoGenException.upstream("post-production-service gave no answer about the frame");
            }
            return answer;
        } catch (WebClientResponseException ex) {
            log.warn("Frame request failed status={} body={}", ex.getStatusCode(), ex.getResponseBodyAsString());
            throw VideoGenException.upstream("Could not ask for the previous shot's last frame -- try again.");
        } catch (VideoGenException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw VideoGenException.upstream("Could not ask for the previous shot's last frame -- try again. (" + ex.getMessage() + ")");
        }
    }
}
