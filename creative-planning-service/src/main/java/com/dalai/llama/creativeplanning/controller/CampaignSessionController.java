package com.dalai.llama.creativeplanning.controller;

import com.dalai.llama.creativeplanning.dto.CampaignPlanningMessageView;
import com.dalai.llama.creativeplanning.dto.CampaignSessionView;
import com.dalai.llama.creativeplanning.dto.CreateCampaignSessionRequest;
import com.dalai.llama.creativeplanning.dto.LockedIdeaView;
import com.dalai.llama.creativeplanning.dto.SendMessageRequest;
import com.dalai.llama.creativeplanning.service.CampaignPlanningChatService;
import com.dalai.llama.creativeplanning.service.CampaignSessionService;
import com.dalai.llama.creativeplanning.service.LockedIdeaService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
public class CampaignSessionController extends BaseController {

    private final CampaignSessionService campaignSessionService;
    private final CampaignPlanningChatService campaignPlanningChatService;
    private final LockedIdeaService lockedIdeaService;

    public CampaignSessionController(
            CampaignSessionService campaignSessionService,
            CampaignPlanningChatService campaignPlanningChatService,
            LockedIdeaService lockedIdeaService
    ) {
        this.campaignSessionService = campaignSessionService;
        this.campaignPlanningChatService = campaignPlanningChatService;
        this.lockedIdeaService = lockedIdeaService;
    }

    @PostMapping("/v1/campaign-sessions")
    public ResponseEntity<CampaignSessionView> create(@Valid @RequestBody CreateCampaignSessionRequest request) {
        return ResponseEntity.ok(campaignSessionService.create(tenant().tenantId(), request));
    }

    @GetMapping("/v1/campaign-sessions")
    public ResponseEntity<List<CampaignSessionView>> list() {
        return ResponseEntity.ok(campaignSessionService.list(tenant().tenantId()));
    }

    @GetMapping("/v1/campaign-sessions/{sessionId}")
    public ResponseEntity<CampaignSessionView> get(@PathVariable UUID sessionId) {
        return ResponseEntity.ok(campaignSessionService.get(tenant().tenantId(), sessionId));
    }

    @PostMapping("/v1/campaign-sessions/{sessionId}/messages")
    public ResponseEntity<CampaignPlanningMessageView> sendMessage(
            @PathVariable UUID sessionId, @Valid @RequestBody SendMessageRequest request) {
        return ResponseEntity.ok(campaignPlanningChatService.sendMessage(tenant().tenantId(), sessionId, request));
    }

    @GetMapping("/v1/campaign-sessions/{sessionId}/messages")
    public ResponseEntity<List<CampaignPlanningMessageView>> messages(@PathVariable UUID sessionId) {
        return ResponseEntity.ok(campaignPlanningChatService.history(tenant().tenantId(), sessionId));
    }

    @PostMapping("/v1/campaign-sessions/{sessionId}/lock-idea")
    public ResponseEntity<LockedIdeaView> lockIdea(@PathVariable UUID sessionId) {
        return ResponseEntity.ok(lockedIdeaService.lock(tenant().tenantId(), sessionId));
    }
}
