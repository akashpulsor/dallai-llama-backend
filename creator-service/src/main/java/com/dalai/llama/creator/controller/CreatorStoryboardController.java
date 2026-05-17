package com.dalai.llama.creator.controller;

import com.dalai.llama.creator.dto.request.GenerateStoryboardRequest;
import com.dalai.llama.creator.dto.response.StoryboardResponse;
import com.dalai.llama.creator.service.StoryboardService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/creator/storyboards")
public class CreatorStoryboardController {

    private final StoryboardService storyboardService;

    public CreatorStoryboardController(StoryboardService storyboardService) {
        this.storyboardService = storyboardService;
    }

    @PostMapping("/scripts/{scriptId}/generate")
    public ResponseEntity<StoryboardResponse> generateFromFinalScript(
            @PathVariable UUID scriptId,
            @Valid @RequestBody(required = false) GenerateStoryboardRequest request,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(storyboardService.generateFromFinalScript(scriptId, request, tenantId, userId));
    }
}
