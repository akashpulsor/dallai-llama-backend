package com.dalai.llama.videogen.controller;

import com.dalai.llama.videogen.domain.entity.ExportBundle;
import com.dalai.llama.videogen.dto.ExportBundleView;
import com.dalai.llama.videogen.dto.ExportRequest;
import com.dalai.llama.videogen.dto.GenerateBatchRequest;
import com.dalai.llama.videogen.dto.GenerateBatchResponse;
import com.dalai.llama.videogen.dto.GenerateShotRequest;
import com.dalai.llama.videogen.dto.GenerateShotResponse;
import com.dalai.llama.videogen.dto.ProjectConfigUpdate;
import com.dalai.llama.videogen.dto.ProjectConfigView;
import com.dalai.llama.videogen.dto.RejectRequest;
import com.dalai.llama.videogen.dto.ShotPromptView;
import com.dalai.llama.videogen.dto.VideoGenJobView;
import com.dalai.llama.videogen.service.ExportBundleService;
import com.dalai.llama.videogen.service.ProjectConfigService;
import com.dalai.llama.videogen.service.ShotGenerationOrchestrator;
import com.dalai.llama.videogen.web.TenantContext;
import com.dalai.llama.videogen.web.TenantContextHolder;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
public class VideoGenController {

    private final ShotGenerationOrchestrator orchestrator;
    private final ExportBundleService exportBundleService;
    private final ProjectConfigService projectConfigService;

    public VideoGenController(
            ShotGenerationOrchestrator orchestrator,
            ExportBundleService exportBundleService,
            ProjectConfigService projectConfigService
    ) {
        this.orchestrator = orchestrator;
        this.exportBundleService = exportBundleService;
        this.projectConfigService = projectConfigService;
    }

    @PostMapping("/v1/shots/generate")
    public ResponseEntity<GenerateShotResponse> generate(@Valid @RequestBody GenerateShotRequest request) {
        return ResponseEntity.ok(orchestrator.generate(tenant(), request));
    }

    @PostMapping("/v1/projects/{projectId}/generate-batch")
    public ResponseEntity<GenerateBatchResponse> generateBatch(
            @PathVariable UUID projectId, @Valid @RequestBody GenerateBatchRequest request
    ) {
        return ResponseEntity.ok(orchestrator.generateBatch(tenant(), projectId, request));
    }

    @GetMapping("/v1/prompts/{promptId}")
    public ResponseEntity<ShotPromptView> getPrompt(@PathVariable UUID promptId) {
        return ResponseEntity.ok(orchestrator.getPrompt(tenant().tenantId(), promptId));
    }

    @GetMapping("/v1/shots/{shotRef}/prompts")
    public ResponseEntity<List<ShotPromptView>> listPromptsForShot(@PathVariable String shotRef) {
        return ResponseEntity.ok(orchestrator.listPromptsForShot(tenant().tenantId(), shotRef));
    }

    /** Latest job per shot_ref for a project -- lets a downstream caller (post-production-service's
     * PROJECT-scope flow) discover every shot in the project and which are COMPLETED, without
     * already knowing the shot_ref list up front. */
    @GetMapping("/v1/projects/{projectId}/jobs")
    public ResponseEntity<List<VideoGenJobView>> listJobsForProject(@PathVariable UUID projectId) {
        return ResponseEntity.ok(orchestrator.listJobsForProject(tenant().tenantId(), projectId));
    }

    /** UI-facing: pulls the generated clip out of MinIO (via a short-lived signed URL) and hands
     * the browser straight to it -- 302 so a plain &lt;video src="..."&gt; tag just works, no
     * separate JSON-then-fetch round trip needed. */
    @GetMapping("/v1/jobs/{jobId}/video")
    public ResponseEntity<Void> getVideo(@PathVariable UUID jobId) {
        String signedUrl = orchestrator.getVideoUrl(tenant().tenantId(), jobId);
        return ResponseEntity.status(302).location(java.net.URI.create(signedUrl)).build();
    }

    @PostMapping("/v1/jobs/{jobId}/approve")
    public ResponseEntity<VideoGenJobView> approve(@PathVariable UUID jobId) {
        return ResponseEntity.ok(orchestrator.approve(tenant(), jobId));
    }

    @PostMapping("/v1/jobs/{jobId}/reject")
    public ResponseEntity<VideoGenJobView> reject(@PathVariable UUID jobId, @RequestBody(required = false) RejectRequest request) {
        return ResponseEntity.ok(orchestrator.reject(tenant(), jobId, request));
    }

    @PostMapping("/v1/jobs/{jobId}/cancel")
    public ResponseEntity<VideoGenJobView> cancel(@PathVariable UUID jobId) {
        return ResponseEntity.ok(orchestrator.cancel(tenant(), jobId));
    }

    @GetMapping("/v1/projects/{projectId}/config")
    public ResponseEntity<ProjectConfigView> getProjectConfig(@PathVariable UUID projectId) {
        TenantContext ctx = tenant();
        var flags = projectConfigService.getEffectiveFlags(ctx.tenantId(), projectId);
        boolean autoApprove = projectConfigService.isAutoApprove(ctx.tenantId(), projectId);
        String voiceCloneModel = projectConfigService.getPreferredVoiceCloneModel(ctx.tenantId(), projectId);
        return ResponseEntity.ok(new ProjectConfigView(projectId, flags, autoApprove, voiceCloneModel));
    }

    @PutMapping("/v1/projects/{projectId}/config")
    public ResponseEntity<ProjectConfigView> updateProjectConfig(
            @PathVariable UUID projectId, @RequestBody ProjectConfigUpdate request
    ) {
        TenantContext ctx = tenant();
        var config = projectConfigService.updateDefaults(ctx.tenantId(), projectId, request.defaultFlags(), request.autoApprove());
        return ResponseEntity.ok(new ProjectConfigView(
                projectId,
                new com.dalai.llama.videogen.dto.FeatureFlags(config.getDefaultDialogueFlag(), config.getDefaultCaptionsFlag()),
                Boolean.TRUE.equals(config.getAutoApprove()),
                config.getPreferredVoiceCloneModel()
        ));
    }

    /** Separate from {@link #updateProjectConfig} so picking a voice-clone model never has to
     * also resend the dialogue/captions flags it knows nothing about. */
    @PutMapping("/v1/projects/{projectId}/config/voice-clone-model")
    public ResponseEntity<ProjectConfigView> updateVoiceCloneModel(
            @PathVariable UUID projectId, @RequestBody UpdateVoiceCloneModelRequest request
    ) {
        TenantContext ctx = tenant();
        var config = projectConfigService.updateVoiceCloneModel(ctx.tenantId(), projectId, request.modelId());
        var flags = projectConfigService.getEffectiveFlags(ctx.tenantId(), projectId);
        return ResponseEntity.ok(new ProjectConfigView(projectId, flags, Boolean.TRUE.equals(config.getAutoApprove()),
                config.getPreferredVoiceCloneModel()));
    }

    public record UpdateVoiceCloneModelRequest(String modelId) {
    }

    @PostMapping("/v1/exports")
    public ResponseEntity<ExportBundleView> requestExport(@RequestBody ExportRequest request) {
        ExportBundle bundle = exportBundleService.requestExport(tenant().tenantId(), request.promptId());
        return ResponseEntity.ok(toView(bundle));
    }

    @GetMapping("/v1/exports/{bundleId}")
    public ResponseEntity<ExportBundleView> getExport(@PathVariable UUID bundleId) {
        ExportBundle bundle = exportBundleService.getStatus(tenant().tenantId(), bundleId);
        return ResponseEntity.ok(toView(bundle));
    }

    private TenantContext tenant() {
        return TenantContextHolder.get();
    }

    private ExportBundleView toView(ExportBundle bundle) {
        return new ExportBundleView(bundle.getBundleId(), bundle.getStatus().name(), bundle.getObjectKey());
    }
}
