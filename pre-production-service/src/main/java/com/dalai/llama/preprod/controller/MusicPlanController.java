package com.dalai.llama.preprod.controller;

import com.dalai.llama.preprod.dto.music.MusicPlan;
import com.dalai.llama.preprod.service.music.MusicDirectorPlannerService;
import com.dalai.llama.preprod.service.music.ProjectScoreGenerationService;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The project's score: plan it, read it, edit the prompt, then generate it.
 *
 * <p>Planning and generation are separate endpoints because they cost different things. Planning
 * is a text call the creator can re-run while tuning; generation is billable audio. Nothing here
 * generates as a side effect of planning.
 *
 * <p>Per-shot background music ({@code /v1/shots/{shotId}/background-music}) is untouched and
 * still works. This is the whole-video score, which is a different thing: one composition whose
 * sections follow the story rather than the cut.
 */
@RestController
public class MusicPlanController extends BaseController {

    private final MusicDirectorPlannerService plannerService;
    private final ProjectScoreGenerationService scoreGenerationService;

    public MusicPlanController(MusicDirectorPlannerService plannerService,
                               ProjectScoreGenerationService scoreGenerationService) {
        this.plannerService = plannerService;
        this.scoreGenerationService = scoreGenerationService;
    }

    /** Plans the score across the whole video. Requires a fixed shot list -- the timeline the
     * music has to cover comes from the shots, not from anything the model picks. */
    @PostMapping("/v1/projects/{projectId}/music-plan")
    public ResponseEntity<MusicPlan> plan(@PathVariable UUID projectId) {
        return ResponseEntity.ok(plannerService.plan(tenant().tenantId(), projectId));
    }

    @GetMapping("/v1/projects/{projectId}/music-plan")
    public ResponseEntity<MusicPlan> get(@PathVariable UUID projectId) {
        return ResponseEntity.ok(plannerService.get(tenant().tenantId(), projectId));
    }

    /** Hand-edit the generation prompt without re-planning. The structured plan is left as it
     * was, so the edit survives until the creator asks for something else. */
    @PutMapping("/v1/projects/{projectId}/music-plan/master-prompt")
    public ResponseEntity<MusicPlan> updateMasterPrompt(@PathVariable UUID projectId,
                                                        @RequestBody UpdateMasterPromptRequest request) {
        return ResponseEntity.ok(
                plannerService.updateMasterPrompt(tenant().tenantId(), projectId, request.masterPrompt()));
    }

    /** Throw away a prompt edit and rebuild it from the stored plan. */
    @PostMapping("/v1/projects/{projectId}/music-plan/master-prompt/recompose")
    public ResponseEntity<MusicPlan> recomposeMasterPrompt(@PathVariable UUID projectId) {
        return ResponseEntity.ok(plannerService.recomposeMasterPrompt(tenant().tenantId(), projectId));
    }

    /** Renders the plan into one continuous track. {@code model} optionally overrides the
     * configured music model -- a registered model id, not a provider name. */
    @PostMapping("/v1/projects/{projectId}/music-plan/generate")
    public ResponseEntity<ProjectScoreGenerationService.ScoreView> generate(
            @PathVariable UUID projectId,
            @RequestParam(required = false) String model) {
        return ResponseEntity.ok(scoreGenerationService.generate(tenant().tenantId(), projectId, model));
    }

    @GetMapping("/v1/projects/{projectId}/music-plan/score")
    public ResponseEntity<ProjectScoreGenerationService.ScoreView> score(@PathVariable UUID projectId) {
        return ResponseEntity.ok(scoreGenerationService.get(tenant().tenantId(), projectId));
    }

    public record UpdateMasterPromptRequest(@NotBlank String masterPrompt) {}
}
