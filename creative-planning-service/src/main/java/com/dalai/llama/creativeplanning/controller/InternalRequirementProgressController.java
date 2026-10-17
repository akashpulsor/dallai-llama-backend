package com.dalai.llama.creativeplanning.controller;

import com.dalai.llama.creativeplanning.dto.RequirementProgressView;
import com.dalai.llama.creativeplanning.service.requirement.ProjectRequirementService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** tenant-service -> here, for a brand's own page: has the brief a creator made from their request
 * been paid, and which project did it become. Same internal, no-JWT convention as
 * {@link InternalNextBriefController}. */
@RestController
public class InternalRequirementProgressController {

    private final ProjectRequirementService projectRequirementService;

    public InternalRequirementProgressController(ProjectRequirementService projectRequirementService) {
        this.projectRequirementService = projectRequirementService;
    }

    @GetMapping("/api/v1/internal/tenants/{tenantId}/project-requirements/{requirementId}/progress")
    public ResponseEntity<RequirementProgressView> progress(@PathVariable UUID tenantId, @PathVariable UUID requirementId) {
        return ResponseEntity.of(projectRequirementService.progress(tenantId, requirementId));
    }
}
