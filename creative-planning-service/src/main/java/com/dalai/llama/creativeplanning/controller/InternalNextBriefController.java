package com.dalai.llama.creativeplanning.controller;

import com.dalai.llama.creativeplanning.dto.NextBriefView;
import com.dalai.llama.creativeplanning.service.requirement.ProjectRequirementService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** pre-production-service -> here, once a client has locked a video on its review page and asks to
 * start their next brief. Pre-production owns the review token and the lock, so it is the one that
 * decides the client may; this only creates (or returns) the brief. Same internal, no-JWT
 * convention as {@link InternalProjectPricingController}. */
@RestController
public class InternalNextBriefController {

    private final ProjectRequirementService projectRequirementService;

    public InternalNextBriefController(ProjectRequirementService projectRequirementService) {
        this.projectRequirementService = projectRequirementService;
    }

    @PostMapping("/api/v1/internal/tenants/{tenantId}/projects/{projectId}/next-brief")
    public ResponseEntity<NextBriefView> startNextBrief(@PathVariable UUID tenantId, @PathVariable UUID projectId) {
        return ResponseEntity.ok(projectRequirementService.startNextBrief(tenantId, projectId));
    }
}
