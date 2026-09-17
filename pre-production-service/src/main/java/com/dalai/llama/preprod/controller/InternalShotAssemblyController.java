package com.dalai.llama.preprod.controller;

import com.dalai.llama.preprod.domain.ShotImageKind;
import com.dalai.llama.preprod.dto.CameraPlanView;
import com.dalai.llama.preprod.dto.CastAssignmentView;
import com.dalai.llama.preprod.dto.CastProfileView;
import com.dalai.llama.preprod.dto.ContinuityBibleView;
import com.dalai.llama.preprod.dto.LightingPlanView;
import com.dalai.llama.preprod.dto.PrepareBundleView;
import com.dalai.llama.preprod.dto.ProjectConfigView;
import com.dalai.llama.preprod.dto.ScriptView;
import com.dalai.llama.preprod.dto.ShotDialogueView;
import com.dalai.llama.preprod.dto.ShotBackgroundMusicView;
import com.dalai.llama.preprod.dto.ShotDialogueBeatView;
import com.dalai.llama.preprod.dto.ShotImageView;
import com.dalai.llama.preprod.dto.ShotProductReferenceView;
import com.dalai.llama.preprod.dto.ShotView;
import com.dalai.llama.preprod.dto.UpdateShotRequest;
import com.dalai.llama.preprod.service.DialogueDetailsService;
import com.dalai.llama.preprod.service.CameraPlanService;
import com.dalai.llama.preprod.service.CastAssignmentService;
import com.dalai.llama.preprod.service.CastProfileService;
import com.dalai.llama.preprod.service.ContinuityBibleService;
import com.dalai.llama.preprod.service.LightingPlanService;
import com.dalai.llama.preprod.service.PrepareBundleAssembler;
import com.dalai.llama.preprod.service.ProjectConfigService;
import com.dalai.llama.preprod.service.ScriptGenerationService;
import com.dalai.llama.preprod.service.ShotBackgroundMusicService;
import com.dalai.llama.preprod.service.ShotDialogueBeatService;
import com.dalai.llama.preprod.service.ShotImageService;
import com.dalai.llama.preprod.service.ShotListGenerationService;
import com.dalai.llama.preprod.service.ShotProductReferenceService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Read-only aliases of the tenant-JWT-authenticated {@code /v1/**} endpoints video-generation-
 * service needs to assemble a prepare-scene prompt. Same {@code /api/v1/internal/**} convention
 * every other cross-service caller uses (see {@code InternalMarketingPlanController} in
 * creative-planning-service, {@code InternalBillingController} in billing-service): permitAll
 * (matched by {@link com.dalai.llama.preprod.config.SecurityConfig}'s internal filter chain),
 * tenantId resolved from the path segment rather than a JWT.
 *
 * <p>Every method is a thin delegate to the exact same service method the JWT-authenticated
 * sibling already calls -- no new business logic, no behavior change to any existing endpoint.
 * Video-gen's {@code PreProductionServiceClient} is the sole caller.
 */
@RestController
@RequestMapping("/api/v1/internal/tenants/{tenantId}")
public class InternalShotAssemblyController {

    private final ContinuityBibleService continuityBibleService;
    private final ProjectConfigService projectConfigService;
    private final CastAssignmentService castAssignmentService;
    private final CastProfileService castProfileService;
    private final ScriptGenerationService scriptGenerationService;
    private final ShotListGenerationService shotListGenerationService;
    private final ShotDialogueBeatService shotDialogueBeatService;
    private final CameraPlanService cameraPlanService;
    private final LightingPlanService lightingPlanService;
    private final ShotImageService shotImageService;
    private final ShotBackgroundMusicService shotBackgroundMusicService;
    private final ShotProductReferenceService shotProductReferenceService;
    private final PrepareBundleAssembler prepareBundleAssembler;
    private final DialogueDetailsService dialogueDetailsService;

    public InternalShotAssemblyController(
            ContinuityBibleService continuityBibleService,
            ProjectConfigService projectConfigService,
            CastAssignmentService castAssignmentService,
            CastProfileService castProfileService,
            ScriptGenerationService scriptGenerationService,
            ShotListGenerationService shotListGenerationService,
            ShotDialogueBeatService shotDialogueBeatService,
            CameraPlanService cameraPlanService,
            LightingPlanService lightingPlanService,
            ShotImageService shotImageService,
            ShotBackgroundMusicService shotBackgroundMusicService,
            ShotProductReferenceService shotProductReferenceService,
            PrepareBundleAssembler prepareBundleAssembler,
            DialogueDetailsService dialogueDetailsService
    ) {
        this.continuityBibleService = continuityBibleService;
        this.projectConfigService = projectConfigService;
        this.castAssignmentService = castAssignmentService;
        this.castProfileService = castProfileService;
        this.scriptGenerationService = scriptGenerationService;
        this.shotListGenerationService = shotListGenerationService;
        this.shotDialogueBeatService = shotDialogueBeatService;
        this.cameraPlanService = cameraPlanService;
        this.lightingPlanService = lightingPlanService;
        this.shotImageService = shotImageService;
        this.shotBackgroundMusicService = shotBackgroundMusicService;
        this.shotProductReferenceService = shotProductReferenceService;
        this.prepareBundleAssembler = prepareBundleAssembler;
        this.dialogueDetailsService = dialogueDetailsService;
    }

    /** Fat aggregate: everything video-gen needs for a project prepare in one call -- see
     * {@link PrepareBundleAssembler} for what's inside and why the per-endpoint reads still
     * live alongside this (single-purpose reads for other callers). */
    @GetMapping("/projects/{projectId}/prepare-bundle")
    public ResponseEntity<PrepareBundleView> prepareBundle(@PathVariable UUID tenantId, @PathVariable UUID projectId) {
        return ResponseEntity.ok(prepareBundleAssembler.assemble(tenantId, projectId));
    }

    // --- Project-scoped reads (stage 1 + stage 2 preamble) ---

    @GetMapping("/projects/{projectId}/continuity-bible")
    public ResponseEntity<ContinuityBibleView> continuityBible(@PathVariable UUID tenantId, @PathVariable UUID projectId) {
        return ResponseEntity.ok(continuityBibleService.getView(tenantId, projectId));
    }

    @GetMapping("/projects/{projectId}/config")
    public ResponseEntity<ProjectConfigView> projectConfig(@PathVariable UUID tenantId, @PathVariable UUID projectId) {
        return ResponseEntity.ok(projectConfigService.get(tenantId, projectId));
    }

    @GetMapping("/projects/{projectId}/cast-assignments")
    public ResponseEntity<List<CastAssignmentView>> castAssignments(@PathVariable UUID tenantId, @PathVariable UUID projectId) {
        return ResponseEntity.ok(castAssignmentService.list(projectId));
    }

    /** Every cast profile in the tenant's library that's used on this project -- lets video-gen
     * resolve a {@code CastAssignment.castProfileId} to the actual face/voice reference locations
     * without a second per-assignment round trip. */
    @GetMapping("/projects/{projectId}/cast-profiles")
    public ResponseEntity<List<CastProfileView>> castProfiles(@PathVariable UUID tenantId, @PathVariable UUID projectId) {
        return ResponseEntity.ok(castProfileService.list(tenantId, projectId, null));
    }

    /**
     * One shot's dialogue and cast details, for post-production's dub pipeline.
     *
     * <p>The creator-facing {@code GET /v1/projects/{id}/shots/{ref}/dialogue} serves the same thing
     * and has no browser caller at all -- its own javadoc names DialogueSyncCoordinator as who it is
     * for. Being on {@code /v1/**} it requires a JWT, and that pipeline runs on a background thread
     * with no user token to send, so it answered 401 and the dub failed on its very first call.
     *
     * <p>No {@code scriptId} parameter. The creator-facing route accepts one and discards it -- the
     * service method has no such argument -- and the only caller passes null. Carrying a parameter
     * that has never done anything is worse than not having it.
     */
    @GetMapping("/projects/{projectId}/shots/{shotRef}/dialogue")
    public ResponseEntity<ShotDialogueView> shotDialogue(@PathVariable UUID tenantId,
                                                         @PathVariable UUID projectId,
                                                         @PathVariable String shotRef) {
        return ResponseEntity.ok(dialogueDetailsService.getShotDialogue(tenantId, projectId, shotRef));
    }

    @GetMapping("/projects/{projectId}/shots")
    public ResponseEntity<List<ShotView>> shots(@PathVariable UUID tenantId, @PathVariable UUID projectId) {
        return ResponseEntity.ok(shotListGenerationService.list(tenantId, projectId));
    }

    /** Script view including its ScriptCharacterView list -- the missing lookup video-gen's
     * dialogue-beat assembler needs to resolve a beat's characterKey (String) to a
     * scriptCharacterId (UUID), which then joins a CastAssignment to the right CastProfile
     * (voice reference). Same underlying service call {@code GET /v1/projects/{id}/script} makes. */
    @GetMapping("/projects/{projectId}/script")
    public ResponseEntity<ScriptView> script(@PathVariable UUID tenantId, @PathVariable UUID projectId) {
        return ResponseEntity.ok(scriptGenerationService.get(tenantId, projectId));
    }

    // --- Per-shot reads (stage 2) ---

    @GetMapping("/shots/{shotId}/dialogue-beats")
    public ResponseEntity<List<ShotDialogueBeatView>> dialogueBeats(@PathVariable UUID tenantId, @PathVariable UUID shotId) {
        return ResponseEntity.ok(shotDialogueBeatService.list(tenantId, shotId));
    }

    public record SaveBeatClonedVoiceRequest(String clonedVoiceId) {}

    @org.springframework.web.bind.annotation.PutMapping("/shots/{shotId}/dialogue-beats/{beatId}/cloned-voice")
    public ResponseEntity<ShotDialogueBeatView> saveBeatClonedVoice(
            @PathVariable UUID tenantId, @PathVariable UUID shotId, @PathVariable UUID beatId,
            @org.springframework.web.bind.annotation.RequestBody SaveBeatClonedVoiceRequest request) {
        return ResponseEntity.ok(shotDialogueBeatService.saveClonedVoice(tenantId, shotId, beatId, request.clonedVoiceId()));
    }

    public record SaveShotVoiceOverRequest(String voiceOver) {}

    /** Writes a shortened spoken line back onto the shot.
     *
     * <p>video-generation-service shortens dialogue at prepare time when the line cannot be said
     * in the seconds the shot has. That has to land here, not stay in the generation request:
     * the shot is where the creator reads and edits the line, so a shot still showing a line
     * that was not the one performed is a shot nobody can reason about. Persisting first also
     * makes the next prepare build from the same text rather than re-shortening the original
     * and possibly landing somewhere else. */
    @org.springframework.web.bind.annotation.PutMapping("/shots/{shotId}/voice-over")
    public ResponseEntity<ShotView> saveShotVoiceOver(
            @PathVariable UUID tenantId, @PathVariable UUID shotId,
            @org.springframework.web.bind.annotation.RequestBody SaveShotVoiceOverRequest request) {
        return ResponseEntity.ok(shotListGenerationService.updateShot(tenantId, shotId,
                UpdateShotRequest.ofVoiceOver(request.voiceOver())));
    }

    @GetMapping("/shots/{shotId}/camera-plan")
    public ResponseEntity<CameraPlanView> cameraPlan(@PathVariable UUID tenantId, @PathVariable UUID shotId) {
        return ResponseEntity.ok(cameraPlanService.get(tenantId, shotId));
    }

    @GetMapping("/shots/{shotId}/lighting-plan")
    public ResponseEntity<LightingPlanView> lightingPlan(@PathVariable UUID tenantId, @PathVariable UUID shotId) {
        return ResponseEntity.ok(lightingPlanService.get(tenantId, shotId));
    }

    @GetMapping("/shots/{shotId}/images")
    public ResponseEntity<List<ShotImageView>> shotImages(@PathVariable UUID tenantId, @PathVariable UUID shotId) {
        return ResponseEntity.ok(shotImageService.list(tenantId, shotId));
    }

    @GetMapping("/shots/{shotId}/images/{kind}")
    public ResponseEntity<ShotImageView> shotImage(@PathVariable UUID tenantId, @PathVariable UUID shotId, @PathVariable ShotImageKind kind) {
        return ResponseEntity.ok(shotImageService.get(tenantId, shotId, kind));
    }

    @GetMapping("/shots/{shotId}/background-music")
    public ResponseEntity<ShotBackgroundMusicView> backgroundMusic(@PathVariable UUID tenantId, @PathVariable UUID shotId) {
        ShotBackgroundMusicView view = shotBackgroundMusicService.get(tenantId, shotId);
        return view == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(view);
    }

    @GetMapping("/shots/{shotId}/product-reference")
    public ResponseEntity<ShotProductReferenceView> productReference(@PathVariable UUID tenantId, @PathVariable UUID shotId) {
        return ResponseEntity.ok(shotProductReferenceService.get(tenantId, shotId));
    }
}
