package com.dalai.llama.creator.controller;

import com.dalai.llama.creator.dto.request.AudienceDecisionRequest;
import com.dalai.llama.creator.dto.response.AudienceResponse;
import com.dalai.llama.creator.service.AudienceService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/creator/audience")
public class CreatorAudienceController {

    private final AudienceService audienceService;

    public CreatorAudienceController(AudienceService audienceService) {
        this.audienceService = audienceService;
    }

    @PostMapping("/suggest")
    public ResponseEntity<AudienceResponse> suggestAudience(
            @Valid @RequestBody(required = false) AudienceDecisionRequest request,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(audienceService.suggestAudience(request, tenantId, userId));
    }

    @PostMapping("/confirm")
    public ResponseEntity<AudienceResponse> confirmAudience(
            @Valid @RequestBody AudienceDecisionRequest request,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity.ok(audienceService.confirmAudience(request, tenantId, userId));
    }
}
