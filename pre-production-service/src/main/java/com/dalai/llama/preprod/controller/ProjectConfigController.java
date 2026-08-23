package com.dalai.llama.preprod.controller;

import com.dalai.llama.preprod.dto.ProjectConfigView;
import com.dalai.llama.preprod.dto.UpdateProjectConfigRequest;
import com.dalai.llama.preprod.service.ProjectConfigService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class ProjectConfigController extends BaseController {

    private final ProjectConfigService projectConfigService;

    public ProjectConfigController(ProjectConfigService projectConfigService) {
        this.projectConfigService = projectConfigService;
    }

    @GetMapping("/v1/projects/{projectId}/config")
    public ResponseEntity<ProjectConfigView> get(@PathVariable UUID projectId) {
        return ResponseEntity.ok(projectConfigService.get(tenant().tenantId(), projectId));
    }

    @PutMapping("/v1/projects/{projectId}/config")
    public ResponseEntity<ProjectConfigView> update(@PathVariable UUID projectId, @Valid @RequestBody UpdateProjectConfigRequest request) {
        return ResponseEntity.ok(projectConfigService.update(tenant().tenantId(), projectId, request));
    }
}
