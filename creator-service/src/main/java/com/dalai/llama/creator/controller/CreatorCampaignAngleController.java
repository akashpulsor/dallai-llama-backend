package com.dalai.llama.creator.controller;

import com.dalai.llama.creator.dto.request.CampaignAngleSuggestionRequest;
import com.dalai.llama.creator.dto.response.CampaignAngleSuggestionResponse;
import com.dalai.llama.creator.service.CampaignAngleSuggestionService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/creator/angles")
public class CreatorCampaignAngleController {

    private final CampaignAngleSuggestionService campaignAngleSuggestionService;

    public CreatorCampaignAngleController(CampaignAngleSuggestionService campaignAngleSuggestionService) {
        this.campaignAngleSuggestionService = campaignAngleSuggestionService;
    }

    @PostMapping("/suggest")
    public ResponseEntity<CampaignAngleSuggestionResponse> suggest(
            @Valid @RequestBody(required = false) CampaignAngleSuggestionRequest request,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity.ok(campaignAngleSuggestionService.suggest(request, tenantId, userId));
    }
}
