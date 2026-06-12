package com.dalai.llama.creator.controller;

import com.dalai.llama.creator.dto.response.PostProductionProjectResponse;
import com.dalai.llama.creator.service.CreatorPostProductionService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/creator/post-production")
public class CreatorPostProductionController {

    private final CreatorPostProductionService postProductionService;

    public CreatorPostProductionController(CreatorPostProductionService postProductionService) {
        this.postProductionService = postProductionService;
    }

    @GetMapping("/projects")
    public ResponseEntity<List<PostProductionProjectResponse>> listShotReadyProjects(
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity.ok(postProductionService.listShotReadyProjects(tenantId, userId, limit));
    }
}
