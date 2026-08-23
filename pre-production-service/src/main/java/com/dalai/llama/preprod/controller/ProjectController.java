package com.dalai.llama.preprod.controller;

import com.dalai.llama.preprod.dto.ClientReviewLinkView;
import com.dalai.llama.preprod.dto.CreateProjectRequest;
import com.dalai.llama.preprod.dto.ProjectView;
import com.dalai.llama.preprod.service.ProjectService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
public class ProjectController extends BaseController {

    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    @PostMapping("/v1/projects/from-locked-idea")
    public ResponseEntity<ProjectView> createFromLockedIdea(@Valid @RequestBody CreateProjectRequest request) {
        return ResponseEntity.ok(projectService.createFromLockedIdea(tenant().tenantId(), request));
    }

    @GetMapping("/v1/projects/{projectId}")
    public ResponseEntity<ProjectView> get(@PathVariable UUID projectId) {
        return ResponseEntity.ok(projectService.get(tenant().tenantId(), projectId));
    }

    @GetMapping("/v1/projects")
    public ResponseEntity<List<ProjectView>> list() {
        return ResponseEntity.ok(projectService.list(tenant().tenantId()));
    }

    /** Idempotent -- returns the existing client review link if one was already generated. The
     * creator shares this link with their client out of band (copy/paste); it opens the
     * unauthenticated {@code GET /v1/public/projects/{token}} page. */
    @PostMapping("/v1/projects/{projectId}/client-review-link")
    public ResponseEntity<ClientReviewLinkView> ensureClientReviewLink(@PathVariable UUID projectId) {
        return ResponseEntity.ok(new ClientReviewLinkView(projectService.ensureClientReviewToken(tenant().tenantId(), projectId)));
    }
}
