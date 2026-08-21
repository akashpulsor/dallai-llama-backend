package com.dalai.llama.critic.controller;

import com.dalai.llama.critic.dto.CritiqueRequest;
import com.dalai.llama.critic.dto.CritiqueResult;
import com.dalai.llama.critic.dto.CritiqueThoughtView;
import com.dalai.llama.critic.dto.RecordFeedbackRequest;
import com.dalai.llama.critic.dto.SimilarFeedbackView;
import com.dalai.llama.critic.service.CritiqueFeedbackService;
import com.dalai.llama.critic.service.CritiqueThoughtService;
import com.dalai.llama.critic.service.SimilarFeedbackService;
import com.dalai.llama.critic.service.critique.CritiqueOrchestrator;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
public class CritiqueController extends BaseController {

    private final CritiqueOrchestrator critiqueOrchestrator;
    private final CritiqueThoughtService critiqueThoughtService;
    private final CritiqueFeedbackService critiqueFeedbackService;
    private final SimilarFeedbackService similarFeedbackService;

    public CritiqueController(
            CritiqueOrchestrator critiqueOrchestrator,
            CritiqueThoughtService critiqueThoughtService,
            CritiqueFeedbackService critiqueFeedbackService,
            SimilarFeedbackService similarFeedbackService
    ) {
        this.critiqueOrchestrator = critiqueOrchestrator;
        this.critiqueThoughtService = critiqueThoughtService;
        this.critiqueFeedbackService = critiqueFeedbackService;
        this.similarFeedbackService = similarFeedbackService;
    }

    @PostMapping("/v1/critiques")
    public ResponseEntity<CritiqueResult> critique(@Valid @RequestBody CritiqueRequest request) {
        return ResponseEntity.ok(critiqueOrchestrator.critique(tenant().tenantId(), request));
    }

    @GetMapping("/v1/critiques/{sessionId}/thoughts")
    public ResponseEntity<List<CritiqueThoughtView>> thoughts(@PathVariable UUID sessionId) {
        return ResponseEntity.ok(critiqueThoughtService.list(sessionId));
    }

    @PostMapping("/v1/critiques/{sessionId}/feedback")
    public ResponseEntity<Void> feedback(@PathVariable UUID sessionId, @RequestBody RecordFeedbackRequest request) {
        critiqueFeedbackService.record(tenant().tenantId(), sessionId, request);
        return ResponseEntity.noContent().build();
    }

    /** critic-service's own internal API for its embedding-based retrieval -- also the shape a
     * future chat-service tool would call, until/unless that moves to a central project-scoped
     * index (see {@code SimilarFeedbackService}'s architecture note). */
    @GetMapping("/v1/critiques/similar-feedback")
    public ResponseEntity<List<SimilarFeedbackView>> similarFeedback(
            @RequestParam String query, @RequestParam(defaultValue = "5") int limit) {
        return ResponseEntity.ok(similarFeedbackService.findSimilarViews(tenant().tenantId(), query, limit));
    }
}
