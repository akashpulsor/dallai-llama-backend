package com.dalai.llama.creator.controller;

import com.dalai.llama.creator.dto.request.CreatorProjectRequest;
import com.dalai.llama.creator.dto.response.CreatorProjectResponse;
import com.dalai.llama.creator.service.CreatorProjectService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/creator/projects")
public class CreatorProjectController {

    private final CreatorProjectService projectService;

    public CreatorProjectController(CreatorProjectService projectService) {
        this.projectService = projectService;
    }

    @GetMapping
    public ResponseEntity<List<CreatorProjectResponse>> listProjects(
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity.ok(projectService.listProjects(tenantId, userId, limit));
    }

    @GetMapping("/{projectId}")
    public ResponseEntity<CreatorProjectResponse> getProject(
            @PathVariable UUID projectId,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity.ok(projectService.getProject(projectId, tenantId, userId));
    }

    @PostMapping
    public ResponseEntity<CreatorProjectResponse> createProject(
            @RequestBody(required = false) CreatorProjectRequest request,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(projectService.createProject(request, tenantId, userId));
    }
}