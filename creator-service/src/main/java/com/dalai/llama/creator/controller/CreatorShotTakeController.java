package com.dalai.llama.creator.controller;

import com.dalai.llama.creator.dto.request.EnhanceAllShotTakesRequest;
import com.dalai.llama.creator.dto.request.ShotTakeAudioEnhanceRequest;
import com.dalai.llama.creator.dto.request.ShotTakeAudioMixRequest;
import com.dalai.llama.creator.dto.request.ShotTakeConfirmRequest;
import com.dalai.llama.creator.dto.request.ShotTakeEnhanceFeedbackRequest;
import com.dalai.llama.creator.dto.request.ShotTakeEnhancePreviewRequest;
import com.dalai.llama.creator.dto.request.ShotTakeFinalRenderRequest;
import com.dalai.llama.creator.dto.request.ShotTakeMediaAnalysisRequest;
import com.dalai.llama.creator.dto.request.ShotTakePolishedFramesRequest;
import com.dalai.llama.creator.dto.request.ShotTakeSoundGenerateRequest;
import com.dalai.llama.creator.dto.request.ShotTakeSoundTimelineRequest;
import com.dalai.llama.creator.dto.request.ShotTakeStudioPolishRequest;
import com.dalai.llama.creator.dto.response.GenerationJobResponse;
import com.dalai.llama.creator.dto.response.ShotTakeResponse;
import com.dalai.llama.creator.service.CreatorShotTakeAsyncService;
import com.dalai.llama.creator.service.CreatorShotTakeService;
import com.dalai.llama.creator.service.GenerationJobService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/creator/storyboards")
public class CreatorShotTakeController {

    private final CreatorShotTakeService shotTakeService;
    private final CreatorShotTakeAsyncService asyncService;
    private final GenerationJobService generationJobService;

    public CreatorShotTakeController(
            CreatorShotTakeService shotTakeService,
            CreatorShotTakeAsyncService asyncService,
            GenerationJobService generationJobService
    ) {
        this.shotTakeService = shotTakeService;
        this.asyncService = asyncService;
        this.generationJobService = generationJobService;
    }

    @GetMapping("/scripts/{scriptId}/takes")
    public ResponseEntity<List<ShotTakeResponse>> listTakes(
            @PathVariable UUID scriptId,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity.ok(shotTakeService.listTakes(scriptId, tenantId, userId));
    }

    @PostMapping(
            value = "/scripts/{scriptId}/shots/{shotNumber}/takes",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public ResponseEntity<ShotTakeResponse> uploadTake(
            @PathVariable UUID scriptId,
            @PathVariable Integer shotNumber,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "note", required = false) String note,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(shotTakeService.uploadTake(scriptId, shotNumber == null ? 1 : shotNumber, file, note, tenantId, userId));
    }

    @PostMapping(
            value = "/shots/takes/{takeId}/reference-frame",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public ResponseEntity<ShotTakeResponse> uploadReferenceFrame(
            @PathVariable UUID takeId,
            @RequestParam("file") MultipartFile file,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity.ok(shotTakeService.uploadReferenceFrame(takeId, file, tenantId, userId));
    }

    @PostMapping("/shots/takes/{takeId}/media-analysis")
    public ResponseEntity<ShotTakeResponse> saveMediaAnalysis(
            @PathVariable UUID takeId,
            @RequestBody(required = false) ShotTakeMediaAnalysisRequest request,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity.ok(shotTakeService.saveMediaAnalysis(takeId, request, tenantId, userId));
    }

    @PostMapping("/shots/takes/{takeId}/sound-timeline")
    public ResponseEntity<ShotTakeResponse> saveSoundTimeline(
            @PathVariable UUID takeId,
            @RequestBody(required = false) ShotTakeSoundTimelineRequest request,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity.ok(shotTakeService.saveSoundTimeline(takeId, request, tenantId, userId));
    }

    @PostMapping(
            value = "/shots/takes/{takeId}/sound-snippets",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public ResponseEntity<ShotTakeResponse> uploadSoundSnippet(
            @PathVariable UUID takeId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "metadata", required = false) String metadata,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity.ok(shotTakeService.uploadSoundSnippet(takeId, file, metadata, tenantId, userId));
    }

    @PostMapping("/shots/takes/{takeId}/sound-generate-async")
    public ResponseEntity<GenerationJobResponse> generateSound(
            @PathVariable UUID takeId,
            @RequestBody(required = false) ShotTakeSoundGenerateRequest request,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity
                .status(HttpStatus.ACCEPTED)
                .body(generationJobService.toResponse(asyncService.startSoundGeneration(takeId, request, tenantId, userId)));
    }

    @PostMapping("/shots/takes/{takeId}/review-async")
    public ResponseEntity<GenerationJobResponse> reviewTake(
            @PathVariable UUID takeId,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity
                .status(HttpStatus.ACCEPTED)
                .body(generationJobService.toResponse(asyncService.startReview(takeId, tenantId, userId)));
    }

    @PostMapping("/shots/takes/{takeId}/confirm")
    public ResponseEntity<ShotTakeResponse> confirmTake(
            @PathVariable UUID takeId,
            @RequestBody(required = false) ShotTakeConfirmRequest request,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity.ok(shotTakeService.confirmTake(takeId, request, tenantId, userId));
    }

    @GetMapping("/scripts/{scriptId}/accepted-sequence")
    public ResponseEntity<Map<String, Object>> getAcceptedSequence(
            @PathVariable UUID scriptId,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity.ok(shotTakeService.getAcceptedSequencePreview(scriptId, tenantId, userId));
    }

    @PostMapping("/scripts/{scriptId}/accepted-sequence/final-render-async")
    public ResponseEntity<GenerationJobResponse> renderAcceptedSequence(
            @PathVariable UUID scriptId,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity
                .status(HttpStatus.ACCEPTED)
                .body(generationJobService.toResponse(asyncService.startAcceptedSequenceRender(scriptId, tenantId, userId)));
    }

    @PostMapping("/shots/takes/{takeId}/enhance-preview-async")
    public ResponseEntity<GenerationJobResponse> enhancePreview(
            @PathVariable UUID takeId,
            @RequestBody(required = false) ShotTakeEnhancePreviewRequest request,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity
                .status(HttpStatus.ACCEPTED)
                .body(generationJobService.toResponse(asyncService.startEnhancementPreview(takeId, request, tenantId, userId)));
    }

    @PostMapping("/shots/takes/{takeId}/enhance-feedback")
    public ResponseEntity<ShotTakeResponse> enhancementFeedback(
            @PathVariable UUID takeId,
            @RequestBody(required = false) ShotTakeEnhanceFeedbackRequest request,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity.ok(shotTakeService.saveEnhancementFeedback(takeId, request, tenantId, userId));
    }

    @PostMapping("/shots/takes/{takeId}/variants/{variantId}/timeline-clip")
    public ResponseEntity<ShotTakeResponse> applyPreviewToTimeline(
            @PathVariable UUID takeId,
            @PathVariable UUID variantId,
            @RequestBody(required = false) TimelineClipRequest request,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        boolean applied = request == null || request.applied() == null || Boolean.TRUE.equals(request.applied());
        return ResponseEntity.ok(shotTakeService.applyEnhancementPreviewToTimeline(takeId, variantId, applied, tenantId, userId));
    }

    @PostMapping("/shots/takes/{takeId}/studio-polish-async")
    public ResponseEntity<GenerationJobResponse> studioPolish(
            @PathVariable UUID takeId,
            @RequestBody(required = false) ShotTakeStudioPolishRequest request,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity
                .status(HttpStatus.ACCEPTED)
                .body(generationJobService.toResponse(asyncService.startStudioPolish(takeId, request, tenantId, userId)));
    }

    @PostMapping("/shots/takes/{takeId}/enhance-audio-async")
    public ResponseEntity<GenerationJobResponse> enhanceAudio(
            @PathVariable UUID takeId,
            @RequestBody(required = false) ShotTakeAudioEnhanceRequest request,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity
                .status(HttpStatus.ACCEPTED)
                .body(generationJobService.toResponse(asyncService.startAudioEnhancement(takeId, request, tenantId, userId)));
    }

    @PostMapping("/shots/takes/{takeId}/audio-mix-async")
    public ResponseEntity<GenerationJobResponse> mixAudio(
            @PathVariable UUID takeId,
            @RequestBody(required = false) ShotTakeAudioMixRequest request,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity
                .status(HttpStatus.ACCEPTED)
                .body(generationJobService.toResponse(asyncService.startAudioMix(takeId, request, tenantId, userId)));
    }

    @PostMapping("/shots/takes/{takeId}/polished-frames")
    public ResponseEntity<ShotTakeResponse> generatePolishedFrames(
            @PathVariable UUID takeId,
            @RequestBody(required = false) ShotTakePolishedFramesRequest request,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity.ok(shotTakeService.generatePolishedFrameTimeline(takeId, request, tenantId, userId));
    }

    @PostMapping("/shots/takes/{takeId}/final-render-async")
    public ResponseEntity<GenerationJobResponse> renderFinalVideo(
            @PathVariable UUID takeId,
            @RequestBody(required = false) ShotTakeFinalRenderRequest request,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity
                .status(HttpStatus.ACCEPTED)
                .body(generationJobService.toResponse(asyncService.startFinalRender(takeId, request, tenantId, userId)));
    }

    @PostMapping("/scripts/{scriptId}/studio-polish-async")
    public ResponseEntity<GenerationJobResponse> studioPolishAll(
            @PathVariable UUID scriptId,
            @RequestBody(required = false) ShotTakeStudioPolishRequest request,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity
                .status(HttpStatus.ACCEPTED)
                .body(generationJobService.toResponse(asyncService.startStudioPolishAll(scriptId, request, tenantId, userId)));
    }

    @PostMapping(
            value = "/shots/takes/{takeId}/audio-enhance-complete",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public ResponseEntity<ShotTakeResponse> completeAudioEnhancement(
            @PathVariable UUID takeId,
            @RequestParam("variantId") UUID variantId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "metadata", required = false) String metadata,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity.ok(shotTakeService.completeAudioEnhancement(takeId, variantId, file, metadata, tenantId, userId));
    }

    @PostMapping(
            value = "/shots/takes/{takeId}/audio-mix-complete",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public ResponseEntity<ShotTakeResponse> completeAudioMix(
            @PathVariable UUID takeId,
            @RequestParam("variantId") UUID variantId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "metadata", required = false) String metadata,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity.ok(shotTakeService.completeAudioMix(takeId, variantId, file, metadata, tenantId, userId));
    }

    @PostMapping(
            value = "/shots/takes/{takeId}/sound-generate-complete",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public ResponseEntity<ShotTakeResponse> completeSoundGeneration(
            @PathVariable UUID takeId,
            @RequestParam("jobId") UUID jobId,
            @RequestParam("layerId") String layerId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "metadata", required = false) String metadata,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity.ok(shotTakeService.completeSoundGeneration(takeId, jobId, layerId, file, metadata, tenantId, userId));
    }

    @PostMapping(
            value = "/shots/takes/{takeId}/studio-polish-complete",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public ResponseEntity<ShotTakeResponse> completeStudioPolish(
            @PathVariable UUID takeId,
            @RequestParam("variantId") UUID variantId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "metadata", required = false) String metadata,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity.ok(shotTakeService.completeStudioPolish(takeId, variantId, file, metadata, tenantId, userId));
    }

    @PostMapping("/scripts/{scriptId}/enhance-all-async")
    public ResponseEntity<GenerationJobResponse> enhanceAll(
            @PathVariable UUID scriptId,
            @RequestBody(required = false) EnhanceAllShotTakesRequest request,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity
                .status(HttpStatus.ACCEPTED)
                .body(generationJobService.toResponse(asyncService.startEnhanceAll(scriptId, request, tenantId, userId)));
    }

    public record TimelineClipRequest(Boolean applied) {
    }
}


