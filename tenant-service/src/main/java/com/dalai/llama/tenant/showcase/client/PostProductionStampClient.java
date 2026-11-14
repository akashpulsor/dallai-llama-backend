package com.dalai.llama.tenant.showcase.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.UUID;

/** Asks post-production for a creator's uploaded film stamped with the Dalaillama mark (ffmpeg runs
 * there, on its one-at-a-time Kafka consumer). The same id is the same request, so asking on every
 * poll is safe and also answers where the stamp is. */
@Slf4j
@Component
public class PostProductionStampClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final WebClient webClient;

    public PostProductionStampClient(
            WebClient.Builder builder,
            @Value("${services.post-production.url:http://post-production-service.apps.svc.cluster.local:8080}") String baseUrl) {
        this.webClient = builder.baseUrl(baseUrl).build();
    }

    /** status: PENDING until made, DONE with {@code stampedUrl}, FAILED (no attempts left) with {@code error}. */
    public record Stamp(String status, String stampedUrl, String error) {
        public boolean failed() {
            return "FAILED".equals(status);
        }
    }

    record StampRequest(UUID tenantId, String bucket, String objectKey, Integer width) {
    }

    public Stamp request(UUID id, UUID tenantId, String bucket, String objectKey, Integer width) {
        try {
            return webClient.put()
                    .uri("/api/v1/internal/upload-stamps/{id}", id)
                    .bodyValue(new StampRequest(tenantId, bucket, objectKey, width))
                    .retrieve()
                    .bodyToMono(Stamp.class)
                    .block(TIMEOUT);
        } catch (WebClientResponseException e) {
            log.error("post-production upload-stamp failed: {} {}", e.getStatusCode().value(), e.getResponseBodyAsString());
            throw new UpstreamUnavailableException("The stamp service is unavailable right now", e);
        }
    }
}
