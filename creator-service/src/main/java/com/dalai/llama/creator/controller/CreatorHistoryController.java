package com.dalai.llama.creator.controller;

import com.dalai.llama.creator.dto.response.CreatorHistoryItemResponse;
import com.dalai.llama.creator.service.CreatorHistoryService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/creator/history")
public class CreatorHistoryController {

    private final CreatorHistoryService historyService;

    public CreatorHistoryController(CreatorHistoryService historyService) {
        this.historyService = historyService;
    }

    @GetMapping("/storylines")
    public ResponseEntity<List<CreatorHistoryItemResponse>> listStorylines(
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity.ok(historyService.listStorylines(tenantId, userId, limit));
    }

    @GetMapping("/storylines/{storyIdeaId}")
    public ResponseEntity<CreatorHistoryItemResponse> getStoryline(
            @PathVariable UUID storyIdeaId,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity.ok(historyService.getStoryline(storyIdeaId, tenantId, userId));
    }

    @GetMapping("/scripts")
    public ResponseEntity<List<CreatorHistoryItemResponse>> listScripts(
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity.ok(historyService.listScripts(tenantId, userId, limit));
    }

    @GetMapping("/scripts/{scriptId}")
    public ResponseEntity<CreatorHistoryItemResponse> getScript(
            @PathVariable UUID scriptId,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity.ok(historyService.getScript(scriptId, tenantId, userId));
    }

    @GetMapping("/storyboards")
    public ResponseEntity<List<CreatorHistoryItemResponse>> listStoryboards(
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity.ok(historyService.listStoryboards(tenantId, userId, limit));
    }

    @GetMapping("/storyboards/{storyboardId}")
    public ResponseEntity<CreatorHistoryItemResponse> getStoryboard(
            @PathVariable UUID storyboardId,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity.ok(historyService.getStoryboard(storyboardId, tenantId, userId));
    }
}
