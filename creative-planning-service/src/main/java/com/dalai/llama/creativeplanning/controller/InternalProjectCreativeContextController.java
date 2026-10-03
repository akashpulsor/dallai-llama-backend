package com.dalai.llama.creativeplanning.controller;

import com.dalai.llama.creativeplanning.dto.ProjectCreativeContextView;
import com.dalai.llama.creativeplanning.service.requirement.ProjectCreativeContextService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** pre-production-service -> here, for the Creative Direction stage: the project's locked idea,
 * its brief, and the client's reference images and videos. Same internal, no-JWT convention as
 * {@link InternalProjectPricingController}. */
@RestController
public class InternalProjectCreativeContextController {

    private final ProjectCreativeContextService projectCreativeContextService;

    public InternalProjectCreativeContextController(ProjectCreativeContextService projectCreativeContextService) {
        this.projectCreativeContextService = projectCreativeContextService;
    }

    @GetMapping("/api/v1/internal/tenants/{tenantId}/projects/{projectId}/creative-context")
    public ResponseEntity<ProjectCreativeContextView> get(@PathVariable UUID tenantId, @PathVariable UUID projectId) {
        return ResponseEntity.ok(projectCreativeContextService.forProject(tenantId, projectId));
    }
}
