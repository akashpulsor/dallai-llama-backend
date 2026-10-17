package com.dalai.llama.tenant.leadmanagement.inquiry;

import com.dalai.llama.tenant.leadmanagement.brand.BrandProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.UUID;

/**
 * Where a brand's request has got to after it became a brief: paid or not, and -- once the creator
 * has shared it -- the project's review link. The same link the creator sends, so links already
 * in clients' hands keep working; the brand page is a second way to it. Follows the brief (creative-
 * planning) to the project its locked idea became, then asks pre-production for the shared token.
 */
@Slf4j
@Component
public class ProjectReviewLinks {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    public record Progress(boolean funded, String reviewUrl) {
        static final Progress UNKNOWN = new Progress(false, null);
    }

    record RequirementProgress(boolean funded, UUID projectId) {
    }

    record SharedReviewToken(String token) {
    }

    private final WebClient creativePlanning;
    private final WebClient preProduction;
    private final BrandProperties brandProperties;

    public ProjectReviewLinks(WebClient.Builder builder, BrandProperties brandProperties,
                              @Value("${services.creative-planning.url:http://creative-planning-service.apps.svc.cluster.local:8080}") String creativePlanningUrl,
                              @Value("${services.pre-production.url:http://pre-production-service.apps.svc.cluster.local:8080}") String preProductionUrl) {
        this.creativePlanning = builder.clone().baseUrl(creativePlanningUrl).build();
        this.preProduction = builder.clone().baseUrl(preProductionUrl).build();
        this.brandProperties = brandProperties;
    }

    /** Best effort: the brand page still shows the request when a service is slow or down. */
    public Progress of(UUID creatorTenantId, UUID requirementId) {
        try {
            RequirementProgress progress = creativePlanning.get()
                    .uri("/api/v1/internal/tenants/{tenantId}/project-requirements/{requirementId}/progress", creatorTenantId, requirementId)
                    .retrieve().bodyToMono(RequirementProgress.class).block(TIMEOUT);
            if (progress == null || !progress.funded() || progress.projectId() == null) {
                return new Progress(progress != null && progress.funded(), null);
            }
            SharedReviewToken token = preProduction.get()
                    .uri("/api/v1/internal/tenants/{tenantId}/projects/{projectId}/client-review-token", creatorTenantId, progress.projectId())
                    .retrieve().bodyToMono(SharedReviewToken.class).block(TIMEOUT);
            String url = token == null || token.token() == null ? null : brandProperties.reviewPageUrl() + token.token();
            return new Progress(true, url);
        } catch (WebClientResponseException e) {
            log.warn("Review link for brief {} unavailable: {}", requirementId, e.getStatusCode().value());
            return Progress.UNKNOWN;
        } catch (RuntimeException e) {
            log.warn("Review link for brief {} unavailable: {}", requirementId, e.getMessage());
            return Progress.UNKNOWN;
        }
    }
}
