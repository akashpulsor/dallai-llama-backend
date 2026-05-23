package com.dalai.llama.creator.controller;

import com.dalai.llama.creator.dto.response.GenerationJobResponse;
import com.dalai.llama.creator.service.GenerationJobService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/creator/jobs")
public class CreatorGenerationJobController {

    private final GenerationJobService generationJobService;

    public CreatorGenerationJobController(GenerationJobService generationJobService) {
        this.generationJobService = generationJobService;
    }

    @GetMapping("/{jobId}")
    public ResponseEntity<GenerationJobResponse> getJob(
            @PathVariable UUID jobId,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        try {
            return ResponseEntity.ok(generationJobService.getGenerationJob(jobId, tenantId, userId));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Generation job was not found.");
        }
    }

    @GetMapping
    public ResponseEntity<List<GenerationJobResponse>> listJobs(
            @RequestParam(value = "jobType", required = false) String jobType,
            @RequestParam(value = "lockedIdeaId", required = false) UUID lockedIdeaId,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity.ok(generationJobService.listGenerationJobs(tenantId, userId, jobType, lockedIdeaId));
    }
}
