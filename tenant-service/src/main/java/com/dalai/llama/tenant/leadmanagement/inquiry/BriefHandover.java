package com.dalai.llama.tenant.leadmanagement.inquiry;

import com.dalai.llama.tenant.showcase.client.UpstreamUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * Hands a brief Dalaillama's account made to the creator who took the request: creative-planning
 * moves the brief, billing moves its payments and the wallet credit they gave. Both sides are safe
 * to repeat; when billing fails after the brief moved, the brief is moved back so the two agree.
 */
@Slf4j
@Component
public class BriefHandover {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private final WebClient creativePlanning;
    private final WebClient billing;

    public BriefHandover(WebClient.Builder builder,
                         @Value("${services.creative-planning.url:http://creative-planning-service.apps.svc.cluster.local:8080}") String creativePlanningUrl,
                         @Value("${services.billing.url:http://billing-service.apps.svc.cluster.local:8080}") String billingUrl) {
        this.creativePlanning = builder.clone().baseUrl(creativePlanningUrl).build();
        this.billing = builder.clone().baseUrl(billingUrl).build();
    }

    public void handOver(UUID fromTenantId, UUID requirementId, UUID toTenantId) {
        moveBrief(fromTenantId, requirementId, toTenantId);
        try {
            post(billing, "/api/v1/internal/tenants/{tenantId}/project-requirement-payments/{requirementId}/transfer",
                    fromTenantId, requirementId, toTenantId);
        } catch (RuntimeException e) {
            try {
                moveBrief(toTenantId, requirementId, fromTenantId);
            } catch (RuntimeException back) {
                log.error("Brief {} moved to {} but its payment didn't; moving it back failed too: {}", requirementId, toTenantId,
                        back.getMessage(), back);
            }
            throw failed(requirementId, e);
        }
    }

    private void moveBrief(UUID from, UUID requirementId, UUID to) {
        try {
            post(creativePlanning, "/api/v1/internal/tenants/{tenantId}/project-requirements/{requirementId}/transfer", from, requirementId, to);
        } catch (WebClientResponseException.Conflict e) {
            throw new IllegalStateException("Production on this brief has already started");
        } catch (RuntimeException e) {
            throw failed(requirementId, e);
        }
    }

    private static void post(WebClient client, String uri, UUID from, UUID requirementId, UUID to) {
        client.post().uri(uri, from, requirementId).bodyValue(Map.of("toTenantId", to)).retrieve().toBodilessEntity().block(TIMEOUT);
    }

    private static RuntimeException failed(UUID requirementId, RuntimeException e) {
        if (e instanceof IllegalStateException || e instanceof UpstreamUnavailableException) return e;
        log.error("Could not hand over brief {}: {}", requirementId, e.getMessage(), e);
        return new UpstreamUnavailableException("Couldn't hand the brief over right now; please try again", e);
    }
}
