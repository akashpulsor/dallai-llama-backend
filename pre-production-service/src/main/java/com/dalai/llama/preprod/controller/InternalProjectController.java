package com.dalai.llama.preprod.controller;

import com.dalai.llama.preprod.dto.CreateProjectRequest;
import com.dalai.llama.preprod.dto.ProjectView;
import com.dalai.llama.preprod.dto.SwitchLockedIdeaRequest;
import com.dalai.llama.preprod.service.ProjectService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Service-to-service mirror of {@code ProjectController#createFromLockedIdea} -- same
 * {@link ProjectService} call, same {@link CreateProjectRequest} shape, just reachable under
 * {@code /api/v1/internal/**} (permitAll, no JWT -- see SecurityConfig) with the tenant taken
 * from the path instead of a caller's JWT. Exists because creative-planning-service's standalone-
 * brief lock flow needs to create a project as itself, not on behalf of a signed-in user with a
 * token -- the same reason chat-service and llm-gateway expose their own
 * {@code /api/v1/internal/tenants/{tenantId}/...} entry points.
 */
@RestController
public class InternalProjectController {

    private final ProjectService projectService;

    public InternalProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    @PostMapping("/api/v1/internal/tenants/{tenantId}/projects/from-locked-idea")
    public ResponseEntity<ProjectView> createFromLockedIdea(
            @PathVariable UUID tenantId, @Valid @RequestBody CreateProjectRequest request) {
        return ResponseEntity.ok(projectService.createFromLockedIdea(tenantId, request));
    }

    /** Called by creative-planning-service's ProjectIdeaService#switchToOption after it creates a
     * new LockedIdea for an existing project (a creator picking a different idea for a project
     * that's already past creation). */
    @PostMapping("/api/v1/internal/tenants/{tenantId}/projects/{projectId}/switch-locked-idea")
    public ResponseEntity<ProjectView> switchLockedIdea(
            @PathVariable UUID tenantId, @PathVariable UUID projectId, @Valid @RequestBody SwitchLockedIdeaRequest request) {
        return ResponseEntity.ok(projectService.switchLockedIdea(tenantId, projectId, request.lockedIdeaId()));
    }
}
