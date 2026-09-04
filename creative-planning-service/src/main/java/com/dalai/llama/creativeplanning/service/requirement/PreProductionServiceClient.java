package com.dalai.llama.creativeplanning.service.requirement;

import com.dalai.llama.creativeplanning.domain.BudgetTier;
import com.dalai.llama.creativeplanning.service.CreativePlanningException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.UUID;

/**
 * Synchronously hands a freshly-locked idea off to pre-production-service's project-creation
 * logic. Calls the internal mirror ({@code POST
 * /api/v1/internal/tenants/{tenantId}/projects/from-locked-idea}, permitAll, X-Tenant-ID only)
 * rather than the tenant-facing {@code /v1/projects/from-locked-idea} -- that path requires a
 * real JWT via pre-production-service's {@code apiFilterChain}, which a backend-to-backend call
 * doesn't have, the same reason ChatServiceClient and LlmGatewayClient both target
 * {@code /api/v1/internal/...} paths on their own targets rather than tenant-facing ones. Unlike
 * ChatServiceClient, this is NOT best-effort: locking an idea is meant to produce a real project,
 * so a pre-production-service failure here must fail the lock request rather than leave the
 * creator thinking they're done when nothing was actually created.
 */
@Slf4j
@Component
class PreProductionServiceClient {

    /** Local mirror of pre-production-service's own CreateProjectRequest -- this codebase's
     * convention is each service owns its copy of a cross-service request shape. */
    private record CreateProjectRequest(UUID lockedIdeaId, String name, BudgetTier budgetTier) {
    }

    /** Only the field this client actually needs from pre-production-service's ProjectView. */
    private record ProjectView(UUID id) {
    }

    private record SwitchLockedIdeaRequest(UUID lockedIdeaId) {
    }

    private final WebClient webClient;
    private final int timeoutMs;

    PreProductionServiceClient(
            WebClient.Builder webClientBuilder,
            @Value("${creative-planning.pre-production.base-url}") String baseUrl,
            @Value("${creative-planning.pre-production.timeout-ms}") int timeoutMs
    ) {
        this.webClient = webClientBuilder.baseUrl(baseUrl).build();
        this.timeoutMs = timeoutMs;
    }

    UUID createProjectFromLockedIdea(UUID tenantId, UUID lockedIdeaId, String projectName, BudgetTier budgetTier) {
        try {
            ProjectView project = webClient.post()
                    .uri("/api/v1/internal/tenants/{tenantId}/projects/from-locked-idea", tenantId)
                    .bodyValue(new CreateProjectRequest(lockedIdeaId, projectName, budgetTier))
                    .retrieve()
                    .bodyToMono(ProjectView.class)
                    .block(Duration.ofMillis(timeoutMs));

            if (project == null || project.id() == null) {
                throw CreativePlanningException.upstream("pre-production-service returned no project for locked idea " + lockedIdeaId);
            }
            return project.id();
        } catch (WebClientResponseException ex) {
            log.error("pre-production-service rejected project creation for locked idea {} status={} body={}",
                    lockedIdeaId, ex.getStatusCode(), ex.getResponseBodyAsString());
            throw CreativePlanningException.upstream(
                    "pre-production-service could not create the project: " + ex.getStatusCode());
        } catch (CreativePlanningException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("pre-production-service call failed for locked idea {}: {}", lockedIdeaId, ex.getMessage(), ex);
            throw CreativePlanningException.upstream("pre-production-service is unreachable: " + ex.getMessage());
        }
    }

    /** Repoints an already-created project's current idea -- see ProjectIdeaService#switchToOption.
     * Not best-effort for the same reason {@link #createProjectFromLockedIdea} isn't: the creator
     * needs to know if the switch didn't actually take. */
    void switchLockedIdea(UUID tenantId, UUID projectId, UUID lockedIdeaId) {
        try {
            webClient.post()
                    .uri("/api/v1/internal/tenants/{tenantId}/projects/{projectId}/switch-locked-idea", tenantId, projectId)
                    .bodyValue(new SwitchLockedIdeaRequest(lockedIdeaId))
                    .retrieve()
                    .toBodilessEntity()
                    .block(Duration.ofMillis(timeoutMs));
        } catch (WebClientResponseException ex) {
            log.error("pre-production-service rejected idea switch for project {} status={} body={}",
                    projectId, ex.getStatusCode(), ex.getResponseBodyAsString());
            throw CreativePlanningException.upstream("pre-production-service could not switch the idea: " + ex.getStatusCode());
        } catch (Exception ex) {
            log.error("pre-production-service call failed switching idea for project {}: {}", projectId, ex.getMessage(), ex);
            throw CreativePlanningException.upstream("pre-production-service is unreachable: " + ex.getMessage());
        }
    }
}
