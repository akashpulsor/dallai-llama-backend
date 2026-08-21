package com.dalai.llama.postprod.controller;

import com.dalai.llama.postprod.dto.AudioGenerationView;
import com.dalai.llama.postprod.dto.CreatePostProductionJobRequest;
import com.dalai.llama.postprod.dto.GenerateFoleyRequest;
import com.dalai.llama.postprod.dto.GenerateMusicRequest;
import com.dalai.llama.postprod.dto.PostProductionJobView;
import com.dalai.llama.postprod.dto.PreviewVoiceRequest;
import com.dalai.llama.postprod.dto.VoicePreviewView;
import com.dalai.llama.postprod.service.AudioGenerationResult;
import com.dalai.llama.postprod.service.FoleyGenerationService;
import com.dalai.llama.postprod.service.MusicGenerationService;
import com.dalai.llama.postprod.service.PostProductionOrchestrator;
import com.dalai.llama.postprod.service.VoicePreviewService;
import com.dalai.llama.postprod.service.llmgateway.LlmGatewayClient;
import com.dalai.llama.postprod.service.llmgateway.LlmGatewayModelSummary;
import com.dalai.llama.postprod.web.TenantContext;
import com.dalai.llama.postprod.web.TenantContextHolder;
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
public class PostProductionController {

    private final PostProductionOrchestrator orchestrator;
    private final LlmGatewayClient llmGatewayClient;
    private final FoleyGenerationService foleyGenerationService;
    private final MusicGenerationService musicGenerationService;
    private final VoicePreviewService voicePreviewService;

    public PostProductionController(
            PostProductionOrchestrator orchestrator,
            LlmGatewayClient llmGatewayClient,
            FoleyGenerationService foleyGenerationService,
            MusicGenerationService musicGenerationService,
            VoicePreviewService voicePreviewService
    ) {
        this.orchestrator = orchestrator;
        this.llmGatewayClient = llmGatewayClient;
        this.foleyGenerationService = foleyGenerationService;
        this.musicGenerationService = musicGenerationService;
        this.voicePreviewService = voicePreviewService;
    }

    /** "I want to listen how the cloned voice sounds" -- see VoicePreviewService. */
    @PostMapping("/v1/post-production/voice-profiles/{voiceProfileId}/preview")
    public ResponseEntity<VoicePreviewView> previewVoice(
            @PathVariable UUID voiceProfileId,
            @RequestBody(required = false) PreviewVoiceRequest request
    ) {
        String text = request == null ? null : request.text();
        String model = request == null ? null : request.model();
        var result = voicePreviewService.preview(tenant().tenantId(), voiceProfileId, text, model);
        return ResponseEntity.ok(new VoicePreviewView(result.voiceProfileId(), result.characterRef(), result.language(), result.audioUrl()));
    }

    /** Every candidate model registered for a capability -- e.g. GET .../models?type=lip_sync
     * lists every lip-sync model available to try via CreatePostProductionJobRequest's
     * lipSyncModel override, so a caller isn't guessing model ids blind. */
    @GetMapping("/v1/post-production/models")
    public ResponseEntity<List<LlmGatewayModelSummary>> listModels(@RequestParam(required = false) String type) {
        return ResponseEntity.ok(llmGatewayClient.listModels(tenant().tenantId().toString(), type));
    }

    /** Standalone, directly-testable foley/music generation -- not yet wired into the main
     * dialogue-sync pipeline (see FoleyGenerationService's class comment). Lets a specific
     * fal.ai model be tried and compared before that pipeline placement is decided. */
    @PostMapping("/v1/post-production/foley")
    public ResponseEntity<AudioGenerationView> generateFoley(@Valid @RequestBody GenerateFoleyRequest request) {
        AudioGenerationResult result = foleyGenerationService.generateFoley(
                tenant().tenantId(), "post-prod-foley-test-" + UUID.randomUUID(),
                request.sourceVideoUrl(), request.cueDescription(), request.model());
        return ResponseEntity.ok(new AudioGenerationView(request.model(), result.audioUrl()));
    }

    @PostMapping("/v1/post-production/music")
    public ResponseEntity<AudioGenerationView> generateMusic(@Valid @RequestBody GenerateMusicRequest request) {
        AudioGenerationResult result = musicGenerationService.generateMusic(
                tenant().tenantId(), "post-prod-music-test-" + UUID.randomUUID(),
                request.moodPrompt(), request.durationSeconds(), request.model());
        return ResponseEntity.ok(new AudioGenerationView(request.model(), result.audioUrl()));
    }

    /** scope=PROJECT: move every already-completed shot in the project into post-production at
     * once. scope=SHOT: post-produce a single shot on its own. Runs synchronously (same blocking
     * shape as video-generation-service's own approve()) and returns once every targeted shot has
     * reached a terminal state. */
    @PostMapping("/v1/post-production/jobs")
    public ResponseEntity<List<PostProductionJobView>> createJobs(@Valid @RequestBody CreatePostProductionJobRequest request) {
        TenantContext ctx = tenant();
        return ResponseEntity.ok(orchestrator.createAndRun(ctx.tenantId(), ctx.userId(), request));
    }

    @GetMapping("/v1/post-production/projects/{projectId}/jobs")
    public ResponseEntity<List<PostProductionJobView>> listForProject(@PathVariable UUID projectId) {
        return ResponseEntity.ok(orchestrator.listForProject(tenant().tenantId(), projectId));
    }

    /** The dubbed/lip-synced result -- same "302 to a signed URL" pattern as
     * video-generation-service's own GET /v1/jobs/{id}/video, so a plain &lt;video src="..."&gt;
     * tag just works. */
    @GetMapping("/v1/post-production/jobs/{jobId}/video")
    public ResponseEntity<Void> getVideo(@PathVariable UUID jobId) {
        String signedUrl = orchestrator.getVideoUrl(tenant().tenantId(), jobId);
        return ResponseEntity.status(302).location(java.net.URI.create(signedUrl)).build();
    }

    private TenantContext tenant() {
        return TenantContextHolder.get();
    }
}
