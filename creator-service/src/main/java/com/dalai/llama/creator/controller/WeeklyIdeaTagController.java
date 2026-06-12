package com.dalai.llama.creator.controller;

import com.dalai.llama.creator.service.WeeklyIdeaTagService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/creator/weekly-idea-tags")
public class WeeklyIdeaTagController {

    private final WeeklyIdeaTagService weeklyIdeaTagService;

    public WeeklyIdeaTagController(WeeklyIdeaTagService weeklyIdeaTagService) {
        this.weeklyIdeaTagService = weeklyIdeaTagService;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> latest() {
        return ResponseEntity.ok(weeklyIdeaTagService.latest());
    }

    @PostMapping("/refresh")
    public ResponseEntity<Map<String, Object>> refresh(
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity.ok(weeklyIdeaTagService.refresh(tenantId, userId, "MANUAL_API"));
    }
}
