package com.dalai.llama.videogen.controller;

import com.dalai.llama.videogen.domain.entity.ProjectScenePreparation;
import com.dalai.llama.videogen.dto.FeatureFlags;
import com.dalai.llama.videogen.dto.GenerateShotRequest;
import com.dalai.llama.videogen.dto.ShotPromptView;
import com.dalai.llama.videogen.service.PrepareBatchRunner;
import com.dalai.llama.videogen.service.PrepareOrchestrationService;
import com.dalai.llama.videogen.service.ScenePreparationService;
import com.dalai.llama.videogen.service.ShotContextAssemblyService;
import com.dalai.llama.videogen.service.ShotGenerationOrchestrator;
import com.dalai.llama.videogen.service.VideoGenException;
import com.dalai.llama.videogen.web.TenantContext;
import com.dalai.llama.videogen.web.TenantContextHolder;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * The two-stage prepare-scene flow, on the {@code /v1/scenes} prefix (deliberately not
 * {@code /v1/projects} or {@code /v1/shots} -- both claimed by pre-production-service at Istio
 * routing).
 *
 * <p>Controller is intentionally thin: every non-trivial code path (batch loop + status
 * transitions, project-wide prompt listing) lives in {@link PrepareOrchestrationService}.
 * Stage-1 template prepare and single-shot prepare stay here as one-liners because they're
 * already trivial and swapping through the orchestrator would just add indirection.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/scenes")
public class PrepareSceneController {

    private final ScenePreparationService scenePreparationService;
    private final ShotContextAssemblyService shotContextAssemblyService;
    private final ShotGenerationOrchestrator shotGenerationOrchestrator;
    private final PrepareOrchestrationService prepareOrchestrationService;
    private final PrepareBatchRunner prepareBatchRunner;

    @PostMapping("/projects/{projectId}/prepare")
    public ResponseEntity<ProjectScenePreparationView> prepareProject(@PathVariable UUID projectId) {
        TenantContext ctx = TenantContextHolder.get();
        ProjectScenePreparation prep = scenePreparationService.prepareProject(ctx.tenantId(), projectId);
        return ResponseEntity.ok(toView(prep));
    }

    @GetMapping("/projects/{projectId}/preparation")
    public ResponseEntity<ProjectScenePreparationView> getPreparation(@PathVariable UUID projectId) {
        TenantContext ctx = TenantContextHolder.get();
        return scenePreparationService.getPreparation(ctx.tenantId(), projectId)
                .map(prep -> ResponseEntity.ok(toView(prep)))
                .orElseThrow(() -> VideoGenException.notFound(
                        "Project " + projectId + " has not been prepared yet"));
    }

    @PostMapping("/projects/{projectId}/shots/{shotId}/prepare")
    public ResponseEntity<ShotPromptView> prepareShot(
            @PathVariable UUID projectId,
            @PathVariable UUID shotId,
            @Valid @RequestBody(required = false) PrepareShotRequest request
    ) {
        TenantContext ctx = TenantContextHolder.get();
        ShotContextAssemblyService.PrepareShotOverrides overrides = request == null
                ? new ShotContextAssemblyService.PrepareShotOverrides(null, null, null, null, null)
                : new ShotContextAssemblyService.PrepareShotOverrides(
                        request.featureFlagOverrides(), request.modelPin(), request.durationSecondsOverride(),
                        request.customNotes(), request.resolutionOverride());
        ShotContextAssemblyService.AssembledShot assembled =
                shotContextAssemblyService.assemble(ctx.tenantId(), projectId, shotId, overrides);
        GenerateShotRequest generateRequest = new GenerateShotRequest(
                projectId, assembled.shotContext(), assembled.featureFlagOverrides(), false);
        ShotGenerationOrchestrator.PreparedShot prepared = shotGenerationOrchestrator.prepareShot(ctx, generateRequest, assembled.sources());
        return ResponseEntity.ok(shotGenerationOrchestrator.getPrompt(ctx.tenantId(), prepared.prompt().getPromptId()));
    }

    @PostMapping("/projects/{projectId}/shots/prepare-batch")
    public ResponseEntity<PrepareShotsBatchAcceptedResponse> prepareShotsBatch(
            @PathVariable UUID projectId,
            @Valid @RequestBody(required = false) PrepareShotsBatchRequest request
    ) {
        TenantContext ctx = TenantContextHolder.get();
        ShotContextAssemblyService.PrepareShotOverrides overrides = request == null
                ? new ShotContextAssemblyService.PrepareShotOverrides(null, null, null, null, null)
                : new ShotContextAssemblyService.PrepareShotOverrides(
                        request.featureFlagOverrides(), request.modelPin(), null, null,
                        request.resolutionOverride());
        boolean started = prepareBatchRunner.submit(
                ctx, projectId, request == null ? null : request.shotIds(), overrides);
        // 202, not 200: the batch runs off the request thread and the prompts are not ready yet.
        // The caller watches GET /projects/{id}/preparation for the status and reads prompts from
        // GET /projects/{id}/shot-prompts as they land -- both endpoints it already calls on page
        // load, so progress is just the page refreshing itself.
        return ResponseEntity.accepted().body(new PrepareShotsBatchAcceptedResponse(
                started ? "PREPARING" : "ALREADY_RUNNING",
                started
                        ? "Preparing shots. Poll the project's preparation status for progress."
                        : "A prepare is already running for this project."));
    }



    @GetMapping("/projects/{projectId}/shot-prompts")
    public ResponseEntity<List<ShotPromptView>> listProjectShotPrompts(@PathVariable UUID projectId) {
        TenantContext ctx = TenantContextHolder.get();
        return ResponseEntity.ok(prepareOrchestrationService.listProjectShotPrompts(ctx.tenantId(), projectId));
    }

    @GetMapping("/{promptId}")
    public ResponseEntity<ShotPromptView> getPrompt(@PathVariable UUID promptId) {
        TenantContext ctx = TenantContextHolder.get();
        return ResponseEntity.ok(shotGenerationOrchestrator.getPrompt(ctx.tenantId(), promptId));
    }

    @PutMapping("/{promptId}")
    public ResponseEntity<ShotPromptView> updatePrompt(
            @PathVariable UUID promptId, @Valid @RequestBody UpdateShotPromptRequest request) {
        TenantContext ctx = TenantContextHolder.get();
        return ResponseEntity.ok(shotGenerationOrchestrator.saveEditedPrompt(ctx.tenantId(), promptId, request.positive()));
    }

    private ProjectScenePreparationView toView(ProjectScenePreparation prep) {
        return new ProjectScenePreparationView(prep.getProjectId(), prep.getTemplateText(),
                prep.getPreparedAt(), prep.getUpdatedAt(),
                prep.getStatus() == null ? null : prep.getStatus().name());
    }

    public record ProjectScenePreparationView(UUID projectId, String templateText, OffsetDateTime preparedAt, OffsetDateTime updatedAt, String status) {}

    /** Every override is optional; null leaves the value inherited from the shot / project config.
     * resolutionOverride is a VideoResolution wire value ("480p"/"720p") -- unrecognized/blank
     * values fall back to the provider's own default rather than rejecting the request, see
     * VideoResolution.fromWireValue. */
    public record PrepareShotRequest(
            FeatureFlags featureFlagOverrides,
            String modelPin,
            Integer durationSecondsOverride,
            String customNotes,
            String resolutionOverride
    ) {}

    public record UpdateShotPromptRequest(@jakarta.validation.constraints.NotBlank String positive) {}

    /** {@code shotIds} empty (or the whole body omitted) means "prepare every shot in the
     * project" -- the UI's default "Prepare all shots" action. A non-empty list is the narrowing
     * case: the shots the user ticked.
     *
     * <p>The override fields mirror {@link PrepareShotRequest} and apply to every shot in the
     * batch: the dialogue/captions flags, the pinned video model and the resolution are all
     * project-wide choices the creator makes once above the grid, so the batch has to carry them
     * the same way a single prepare does -- otherwise "Prepare all shots" would silently ignore
     * the selections sitting right next to the button. */
    public record PrepareShotsBatchRequest(
            List<UUID> shotIds,
            FeatureFlags featureFlagOverrides,
            String modelPin,
            String resolutionOverride
    ) {}

    /** What a 202 from prepare-batch carries. {@code status} is PREPARING when this request
     * started a batch, or ALREADY_RUNNING when one was already going for the project -- in which
     * case nothing new was queued and the caller should just watch the existing run. */
    public record PrepareShotsBatchAcceptedResponse(String status, String message) {}

    public record FailedShot(UUID shotId, String reason) {}
}
