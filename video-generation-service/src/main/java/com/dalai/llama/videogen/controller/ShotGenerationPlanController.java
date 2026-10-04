package com.dalai.llama.videogen.controller;

import com.dalai.llama.videogen.dto.generationplan.GenerationPlanRequests;
import com.dalai.llama.videogen.dto.generationplan.PlanGenerationView;
import com.dalai.llama.videogen.dto.generationplan.PromptValidationView;
import com.dalai.llama.videogen.dto.generationplan.ShotGenerationPlanView;
import com.dalai.llama.videogen.service.generationplan.ShotGenerationPlanService;
import com.dalai.llama.videogen.web.TenantContext;
import com.dalai.llama.videogen.web.TenantContextHolder;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The video studio for one shot: analyse, choose settings, build the timeline, write and edit the
 * prompt, check it, attach the previous shot's last frame, and generate. Every step is its own
 * call; only {@code /generate} spends on a render, and it sends the prompt it is given.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/scenes/projects/{projectId}/shots/{shotId}/generation-plan")
public class ShotGenerationPlanController {

    private final ShotGenerationPlanService service;

    @GetMapping
    public ResponseEntity<ShotGenerationPlanView> get(@PathVariable UUID projectId, @PathVariable UUID shotId) {
        return ResponseEntity.ok(service.get(tenant().tenantId(), projectId, shotId));
    }

    /** The prompt-inputs checklist: creative direction, script, story frame, frames, cast, lighting,
     * camera and the rest -- each present or not, with what it says. */
    @GetMapping("/inputs")
    public ResponseEntity<java.util.List<com.dalai.llama.videogen.dto.generationplan.PromptInputView>> inputs(
            @PathVariable UUID projectId, @PathVariable UUID shotId) {
        return ResponseEntity.ok(service.promptInputs(tenant().tenantId(), projectId, shotId));
    }

    @PostMapping("/analyze")
    public ResponseEntity<ShotGenerationPlanView> analyze(@PathVariable UUID projectId, @PathVariable UUID shotId) {
        return ResponseEntity.ok(service.analyze(tenant().tenantId(), projectId, shotId));
    }

    @PutMapping("/settings")
    public ResponseEntity<ShotGenerationPlanView> selectSettings(
            @PathVariable UUID projectId, @PathVariable UUID shotId,
            @Valid @RequestBody GenerationPlanRequests.SelectSettings request) {
        return ResponseEntity.ok(service.selectSettings(tenant().tenantId(), projectId, shotId,
                request.generationDurationSeconds(), request.generationFps()));
    }

    @PostMapping("/timeline")
    public ResponseEntity<ShotGenerationPlanView> buildTimeline(@PathVariable UUID projectId, @PathVariable UUID shotId) {
        return ResponseEntity.ok(service.buildTimeline(tenant().tenantId(), projectId, shotId));
    }

    /** Writes, or rewrites, the AI recommendation. Never touches the creator's saved draft. */
    @PostMapping("/prompt")
    public ResponseEntity<ShotGenerationPlanView> composePrompt(@PathVariable UUID projectId, @PathVariable UUID shotId) {
        return ResponseEntity.ok(service.composePrompt(tenant().tenantId(), projectId, shotId));
    }

    /** 409 when the draft changed since {@code expectedRevision} was read. */
    @PutMapping("/prompt/draft")
    public ResponseEntity<ShotGenerationPlanView> saveDraft(
            @PathVariable UUID projectId, @PathVariable UUID shotId,
            @Valid @RequestBody GenerationPlanRequests.SaveDraft request) {
        return ResponseEntity.ok(service.saveDraft(tenant().tenantId(), shotId, request.prompt(), request.expectedRevision()));
    }

    /** Reset to the AI recommendation. */
    @DeleteMapping("/prompt/draft")
    public ResponseEntity<ShotGenerationPlanView> resetDraft(@PathVariable UUID projectId, @PathVariable UUID shotId) {
        return ResponseEntity.ok(service.resetDraft(tenant().tenantId(), shotId));
    }

    @PostMapping("/prompt/validate")
    public ResponseEntity<PromptValidationView> validatePrompt(
            @PathVariable UUID projectId, @PathVariable UUID shotId,
            @Valid @RequestBody GenerationPlanRequests.ValidatePrompt request) {
        return ResponseEntity.ok(service.validatePrompt(tenant().tenantId(), projectId, shotId, request.prompt()));
    }

    /** Body optional: no {@code sourceShotId} means the shot before this one. */
    @PostMapping("/continuation-frame")
    public ResponseEntity<ShotGenerationPlanView> attachContinuationFrame(
            @PathVariable UUID projectId, @PathVariable UUID shotId,
            @RequestBody(required = false) GenerationPlanRequests.AttachContinuationFrame request) {
        return ResponseEntity.ok(service.attachContinuationFrame(tenant().tenantId(), projectId, shotId,
                request == null ? null : request.sourceShotId()));
    }

    @DeleteMapping("/continuation-frame")
    public ResponseEntity<ShotGenerationPlanView> detachContinuationFrame(@PathVariable UUID projectId, @PathVariable UUID shotId) {
        return ResponseEntity.ok(service.detachContinuationFrame(tenant().tenantId(), shotId));
    }

    /** Generate Video: queues a render of exactly {@code prompt} and returns the job to poll. */
    @PostMapping("/generate")
    public ResponseEntity<PlanGenerationView> generate(
            @PathVariable UUID projectId, @PathVariable UUID shotId,
            @Valid @RequestBody GenerationPlanRequests.Generate request) {
        return ResponseEntity.ok(service.generate(tenant(), projectId, shotId, request.prompt()));
    }

    private TenantContext tenant() {
        return TenantContextHolder.get();
    }
}
