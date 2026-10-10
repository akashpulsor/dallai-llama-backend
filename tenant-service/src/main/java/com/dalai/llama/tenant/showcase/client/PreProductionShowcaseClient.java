package com.dalai.llama.tenant.showcase.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Reads a project's film facts from pre-production-service (mesh-internal). */
@Slf4j
@Component
public class PreProductionShowcaseClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final WebClient webClient;

    public PreProductionShowcaseClient(
            WebClient.Builder builder,
            @Value("${services.pre-production.url:http://pre-production-service.apps.svc.cluster.local:8080}") String baseUrl) {
        this.webClient = builder.baseUrl(baseUrl).build();
    }

    /** @throws IllegalArgumentException when the project doesn't exist or isn't this tenant's */
    public ShowcaseSource source(UUID tenantId, UUID projectId) {
        ShowcaseSource source;
        try {
            source = webClient.get()
                    .uri("/api/v1/internal/tenants/{tenantId}/projects/{projectId}/showcase-source", tenantId, projectId)
                    .retrieve()
                    .bodyToMono(ShowcaseSource.class)
                    .block(TIMEOUT);
        } catch (WebClientResponseException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) throw new IllegalArgumentException("No such project in your account");
            log.error("pre-production showcase-source failed: {} {}", e.getStatusCode().value(), e.getResponseBodyAsString());
            throw new UpstreamUnavailableException("Projects are unavailable right now; please try again", e);
        } catch (RuntimeException e) {
            log.error("pre-production showcase-source failed: {}", e.getMessage(), e);
            throw new UpstreamUnavailableException("Projects are unavailable right now; please try again", e);
        }
        if (source == null) throw new UpstreamUnavailableException("Projects are unavailable right now; please try again", null);
        return source;
    }

    /** Wire mirror of pre-production's {@code ShowcaseSourceService.ShowcaseSourceView}. */
    public record ShowcaseSource(
            UUID projectId,
            String projectName,
            String projectStatus,
            boolean filmReady,
            OffsetDateTime renderedAt,
            BigDecimal durationSeconds,
            Integer width,
            Integer height,
            OffsetDateTime clientLockedAt,
            String marketingTermsVersion,
            OffsetDateTime marketingTermsAcceptedAt,
            long shotImages,
            long clientReviewSessions,
            long clientComments,
            long clientApprovals,
            String downloadUrl
    ) {
    }
}
