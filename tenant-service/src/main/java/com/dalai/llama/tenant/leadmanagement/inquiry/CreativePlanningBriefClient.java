package com.dalai.llama.tenant.leadmanagement.inquiry;

import com.dalai.llama.tenant.showcase.client.UpstreamUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Creates a brief in creative-planning-service on the creator's behalf (mesh-internal). */
@Slf4j
@Component
public class CreativePlanningBriefClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    /** A starting length; the creator edits it on the brief before sending it for payment. */
    private static final int DEFAULT_DURATION_SECONDS = 30;

    private final WebClient webClient;

    public CreativePlanningBriefClient(
            WebClient.Builder builder,
            @Value("${services.creative-planning.url:http://creative-planning-service.apps.svc.cluster.local:8080}") String baseUrl) {
        this.webClient = builder.baseUrl(baseUrl).build();
    }

    public record CreatedBrief(UUID requirementId, String shareToken) {
    }

    /** Wire shape of creative-planning's CreateStandaloneRequirementRequest (no product, no files). */
    record StandaloneBrief(String briefText, Integer durationSeconds, List<String> languages, String tenantType,
                           Map<String, String> brandContext) {
    }

    public CreatedBrief createBrief(UUID tenantId, String briefText, String brandName, String industry) {
        StandaloneBrief body = new StandaloneBrief(briefText, DEFAULT_DURATION_SECONDS, List.of("English"),
                "AI_VIDEO_CREATOR", brandName == null ? null : industry == null
                        ? Map.of("brandName", brandName) : Map.of("brandName", brandName, "industry", industry));
        try {
            CreatedBrief created = webClient.post()
                    .uri("/api/v1/internal/tenants/{tenantId}/project-requirements/standalone", tenantId)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(CreatedBrief.class)
                    .block(TIMEOUT);
            if (created == null) throw new UpstreamUnavailableException("Briefs are unavailable right now", null);
            return created;
        } catch (UpstreamUnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            log.error("Could not create a brief for tenant {}: {}", tenantId, e.getMessage(), e);
            throw new UpstreamUnavailableException("Briefs are unavailable right now; please try again", e);
        }
    }
}
