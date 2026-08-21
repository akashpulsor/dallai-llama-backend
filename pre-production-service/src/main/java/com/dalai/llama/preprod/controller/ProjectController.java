package com.dalai.llama.preprod.controller;

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
}
