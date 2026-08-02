package com.dalai.llama.creator.controller;

import com.dalai.llama.creator.dto.request.GenerateProductionPlanRequest;
import com.dalai.llama.creator.dto.request.GenerateStoryboardRequest;
import com.dalai.llama.creator.dto.request.StoryboardClientReviewChatRequest;
import com.dalai.llama.creator.dto.request.StoryboardClientReviewRequest;
import com.dalai.llama.creator.dto.request.ShotAiEditRequest;
import com.dalai.llama.creator.dto.request.ShotTimelineInsertRequest;
import com.dalai.llama.creator.dto.response.GenerationJobResponse;
import com.dalai.llama.creator.dto.response.ShotProductionPlanTagResponse;
import com.dalai.llama.creator.dto.response.ShotImageUrlResponse;
import com.dalai.llama.creator.dto.response.StoryboardClientReviewResponse;
import com.dalai.llama.creator.dto.response.StoryboardSceneResponse;
import com.dalai.llama.creator.dto.response.StoryboardResponse;
import com.dalai.llama.creator.service.CreatorProductionPlanAsyncService;
import com.dalai.llama.creator.service.CreatorScreenplayVideoAsyncService;
import com.dalai.llama.creator.service.CreatorStoryboardAsyncService;
import com.dalai.llama.creator.service.FounderAvatarPreviewService;
import com.dalai.llama.creator.service.FounderAvatarTestService;
import com.dalai.llama.creator.service.GenerationJobService;
import com.dalai.llama.creator.service.ProductionPlanTagService;
import com.dalai.llama.creator.service.ScreenplayVideoService;
import com.dalai.llama.creator.service.StoryboardService;
import com.dalai.llama.creator.service.StoryboardClientReviewService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/creator/storyboards")
public class CreatorStoryboardController {

    private final StoryboardService storyboardService;
    private final ProductionPlanTagService productionPlanTagService;
    private final CreatorProductionPlanAsyncService asyncProductionPlanService;
    private final CreatorStoryboardAsyncService asyncStoryboardService;
    private final CreatorScreenplayVideoAsyncService asyncScreenplayVideoService;
    private final GenerationJobService generationJobService;
    private final ScreenplayVideoService screenplayVideoService;
    private final FounderAvatarPreviewService founderAvatarPreviewService;
    private final FounderAvatarTestService founderAvatarTestService;
    private final StoryboardClientReviewService storyboardClientReviewService;

    public CreatorStoryboardController(
            StoryboardService storyboardService,
            ProductionPlanTagService productionPlanTagService,
            CreatorProductionPlanAsyncService asyncProductionPlanService,
            CreatorStoryboardAsyncService asyncStoryboardService,
            CreatorScreenplayVideoAsyncService asyncScreenplayVideoService,
            GenerationJobService generationJobService,
            ScreenplayVideoService screenplayVideoService,
            FounderAvatarPreviewService founderAvatarPreviewService,
            FounderAvatarTestService founderAvatarTestService,
            StoryboardClientReviewService storyboardClientReviewService
    ) {
        this.storyboardService = storyboardService;
        this.productionPlanTagService = productionPlanTagService;
        this.asyncProductionPlanService = asyncProductionPlanService;
        this.asyncStoryboardService = asyncStoryboardService;
        this.asyncScreenplayVideoService = asyncScreenplayVideoService;
        this.generationJobService = generationJobService;
        this.screenplayVideoService = screenplayVideoService;
        this.founderAvatarPreviewService = founderAvatarPreviewService;
        this.founderAvatarTestService = founderAvatarTestService;
        this.storyboardClientReviewService = storyboardClientReviewService;
    }

    @GetMapping("/scripts/{scriptId}/plans")
    public ResponseEntity<List<ShotProductionPlanTagResponse>> listShotPlans(
            @PathVariable UUID scriptId,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity.ok(productionPlanTagService.listTagsForScript(scriptId, tenantId, userId));
    }

    @GetMapping("/scripts/{scriptId}/client-review")
    public ResponseEntity<StoryboardClientReviewResponse> getClientReview(
            @PathVariable UUID scriptId,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity.ok(storyboardClientReviewService.getReview(scriptId, tenantId, userId));
    }

    @PutMapping("/scripts/{scriptId}/client-review")
    public ResponseEntity<StoryboardClientReviewResponse> saveClientReview(
            @PathVariable UUID scriptId,
            @Valid @RequestBody StoryboardClientReviewRequest request,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity.ok(storyboardClientReviewService.saveReview(
                scriptId,
                request,
                tenantId,
                userId
        ));
    }

    @PostMapping("/scripts/{scriptId}/client-review/chat")
    public ResponseEntity<Map<String, Object>> chatClientReview(
            @PathVariable UUID scriptId,
            @Valid @RequestBody StoryboardClientReviewChatRequest request,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity.ok(storyboardClientReviewService.chatReview(
                scriptId,
                request,
                tenantId,
                userId
        ));
    }

    @PostMapping("/scripts/{scriptId}/client-review/apply")
    public ResponseEntity<StoryboardClientReviewResponse> applyClientReview(
            @PathVariable UUID scriptId,
            @Valid @RequestBody StoryboardClientReviewRequest request,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity.ok(storyboardClientReviewService.applyReview(
                scriptId,
                request,
                tenantId,
                userId
        ));
    }

    @PostMapping("/scripts/{scriptId}/client-review/revert")
    public ResponseEntity<StoryboardClientReviewResponse> revertClientReview(
            @PathVariable UUID scriptId,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity.ok(storyboardClientReviewService.revertReview(
                scriptId,
                tenantId,
                userId
        ));
    }

    @PostMapping(
            value = "/scripts/{scriptId}/client-review/font-references",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public ResponseEntity<Map<String, Object>> uploadClientReviewFontReference(
            @PathVariable UUID scriptId,
            @RequestParam("file") MultipartFile file,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(storyboardClientReviewService.uploadFontReferenceImage(scriptId, file, tenantId, userId));
    }

    @PostMapping(
            value = "/scripts/{scriptId}/client-review/visual-references",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public ResponseEntity<Map<String, Object>> uploadClientReviewVisualReference(
            @PathVariable UUID scriptId,
            @RequestParam("file") MultipartFile file,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(storyboardClientReviewService.uploadVisualReferenceImage(
                        scriptId, file, tenantId, userId
                ));
    }

    @GetMapping(
            value = "/scripts/{scriptId}/animated-preview",
            produces = MediaType.TEXT_HTML_VALUE
    )
    public ResponseEntity<String> getAnimatedStoryboardPreview(
            @PathVariable UUID scriptId,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .header("Content-Disposition", "inline; filename=\"storyboard-animated.html\"")
                .body(storyboardClientReviewService.buildAnimatedPreview(scriptId, tenantId, userId));
    }

    @GetMapping("/scripts/{scriptId}/shots/images")
    public ResponseEntity<List<ShotImageUrlResponse>> listShotImageUrls(
            @PathVariable UUID scriptId,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity.ok(storyboardService.listShotImageUrls(scriptId, tenantId, userId));
    }

    @PostMapping("/scripts/{scriptId}/plans/generate-async")
    public ResponseEntity<GenerationJobResponse> generateShotPlansAsync(
            @PathVariable UUID scriptId,
            @Valid @RequestBody(required = false) GenerateProductionPlanRequest request,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity
                .status(HttpStatus.ACCEPTED)
                .body(generationJobService.toResponse(asyncProductionPlanService.startProductionPlanGeneration(
                        scriptId,
                        request == null ? null : request.styleKey(),
                        request == null ? null : request.focusedShotNumber(),
                        request != null && Boolean.TRUE.equals(request.forceRegenerate()),
                        request == null ? null : request.videoProvider(),
                        request == null ? null : request.videoModel(),
                        request == null ? null : request.maxClipSeconds(),
                        tenantId,
                        userId
                )));
    }

    @PostMapping("/scripts/{scriptId}/generate")
    public ResponseEntity<StoryboardResponse> generateFromFinalScript(
            @PathVariable UUID scriptId,
            @Valid @RequestBody(required = false) GenerateStoryboardRequest request,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(storyboardService.generateFromFinalScript(scriptId, request, tenantId, userId));
    }

    @PostMapping("/scripts/{scriptId}/generate-async")
    public ResponseEntity<GenerationJobResponse> generateFromFinalScriptAsync(
            @PathVariable UUID scriptId,
            @Valid @RequestBody(required = false) GenerateStoryboardRequest request,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity
                .status(HttpStatus.ACCEPTED)
                .body(generationJobService.toResponse(asyncStoryboardService.startStoryboardGeneration(scriptId, request, tenantId, userId)));
    }

    @PostMapping("/scripts/{scriptId}/videos/generate-async")
    public ResponseEntity<GenerationJobResponse> generateScreenplayVideoAsync(
            @PathVariable UUID scriptId,
            @RequestBody(required = false) Map<String, Object> request,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity
                .status(HttpStatus.ACCEPTED)
                .body(generationJobService.toResponse(asyncScreenplayVideoService.startVideoGeneration(scriptId, request, tenantId, userId)));
    }

    @PostMapping(value = "/scripts/{scriptId}/reference-images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> uploadScreenplayVideoReferenceImage(
            @PathVariable UUID scriptId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "details", required = false) String details,
            @RequestParam(value = "enhanceScreenplay", required = false) Boolean enhanceScreenplay,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(screenplayVideoService.uploadReferenceImage(scriptId, file, details, Boolean.TRUE.equals(enhanceScreenplay), tenantId, userId));
    }

    @PostMapping(value = "/scripts/{scriptId}/founder-avatar-source", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> uploadFounderAvatarSource(
            @PathVariable UUID scriptId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "details", required = false) String details,
            @RequestParam(value = "providerMode", required = false) String providerMode,
            @RequestParam(value = "synthesiaAvatarId", required = false) String synthesiaAvatarId,
            @RequestParam(value = "synthesiaVoiceId", required = false) String synthesiaVoiceId,
            @RequestParam(value = "localVoiceModel", required = false) String localVoiceModel,
            @RequestParam(value = "voiceProfileId", required = false) String voiceProfileId,
            @RequestParam(value = "localTalkingAvatarModel", required = false) String localTalkingAvatarModel,
            @RequestParam(value = "localLipSyncModel", required = false) String localLipSyncModel,
            @RequestParam(value = "localImageModel", required = false) String localImageModel,
            @RequestParam(value = "localVideoModel", required = false) String localVideoModel,
            @RequestParam(value = "referenceTranscript", required = false) String referenceTranscript,
            @RequestParam(value = "previewText", required = false) String previewText,
            @RequestParam(value = "spokenText", required = false) String spokenText,
            @RequestParam(value = "pronunciationGuide", required = false) String pronunciationGuide,
            @RequestParam(value = "elevenLabsVoiceId", required = false) String elevenLabsVoiceId,
            @RequestParam(value = "sarvamVoiceId", required = false) String sarvamVoiceId,
            @RequestParam(value = "productionEnhancementEnabled", required = false) Boolean productionEnhancementEnabled,
            @RequestParam(value = "productionEnhancementPrompt", required = false) String productionEnhancementPrompt,
            @RequestParam(value = "consentConfirmed", required = false) Boolean consentConfirmed,
            @RequestParam(value = "language", required = false) String language,
            @RequestParam(value = "languageCode", required = false) String languageCode,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(screenplayVideoService.uploadFounderAvatarSource(
                        scriptId,
                        file,
                        details,
                        providerMode,
                        synthesiaAvatarId,
                        synthesiaVoiceId,
                        localVoiceModel,
                        voiceProfileId,
                        localTalkingAvatarModel,
                        localLipSyncModel,
                        localImageModel,
                        localVideoModel,
                        referenceTranscript,
                        previewText,
                        spokenText,
                        pronunciationGuide,
                        elevenLabsVoiceId,
                        sarvamVoiceId,
                        Boolean.TRUE.equals(productionEnhancementEnabled),
                        productionEnhancementPrompt,
                        Boolean.TRUE.equals(consentConfirmed),
                        language,
                        languageCode,
                        tenantId,
                        userId
                ));
    }

    @PostMapping("/scripts/{scriptId}/founder-dialogue/english")
    public ResponseEntity<Map<String, Object>> prepareFounderEnglishDialogue(
            @PathVariable UUID scriptId,
            @RequestBody(required = false) Map<String, Object> request,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity.ok(
                screenplayVideoService.prepareFounderEnglishDialogue(scriptId, request, tenantId, userId)
        );
    }

    @PostMapping("/scripts/{scriptId}/founder-voice-preview")
    public ResponseEntity<Map<String, Object>> generateFounderVoicePreview(
            @PathVariable UUID scriptId,
            @RequestBody(required = false) Map<String, Object> request,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity.status(HttpStatus.CREATED).body(
                screenplayVideoService.generateFounderVoicePreview(scriptId, request, tenantId, userId)
        );
    }

    @GetMapping("/scripts/{scriptId}/founder-avatars")
    public ResponseEntity<Map<String, Object>> listReusableFounderAvatars(
            @PathVariable UUID scriptId,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity.ok(screenplayVideoService.listReusableFounderAvatars(scriptId, tenantId, userId));
    }

    @PostMapping("/scripts/{scriptId}/founder-avatar-selection")
    public ResponseEntity<Map<String, Object>> selectReusableFounderAvatar(
            @PathVariable UUID scriptId,
            @RequestBody(required = false) Map<String, Object> request,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity.ok(screenplayVideoService.selectReusableFounderAvatar(scriptId, request, tenantId, userId));
    }

    @PostMapping("/scripts/{scriptId}/founder-voice-approval")
    public ResponseEntity<Map<String, Object>> approveFounderVoicePreview(
            @PathVariable UUID scriptId,
            @RequestBody(required = false) Map<String, Object> request,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity.ok(screenplayVideoService.approveFounderVoicePreview(scriptId, request, tenantId, userId));
    }

    @PostMapping("/scripts/{scriptId}/founder-avatar-preview")
    public ResponseEntity<Map<String, Object>> generateFounderAvatarPreview(
            @PathVariable UUID scriptId,
            @RequestBody(required = false) Map<String, Object> request,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity.status(HttpStatus.CREATED).body(
                founderAvatarPreviewService.generatePreview(scriptId, request, tenantId, userId)
        );
    }

    @PostMapping("/scripts/{scriptId}/founder-avatar-approval")
    public ResponseEntity<Map<String, Object>> approveFounderAvatarPreview(
            @PathVariable UUID scriptId,
            @RequestBody(required = false) Map<String, Object> request,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity.ok(
                founderAvatarPreviewService.updateApproval(scriptId, request, tenantId, userId)
        );
    }

    @PostMapping(value = "/scripts/{scriptId}/founder-avatar-portrait", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> prepareFounderAvatarPortrait(
            @PathVariable UUID scriptId,
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "sourceMode", required = false) String sourceMode,
            @RequestParam(value = "timestampSeconds", required = false) Double timestampSeconds,
            @RequestParam(value = "consentConfirmed", required = false) Boolean consentConfirmed,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity.status(HttpStatus.CREATED).body(founderAvatarTestService.preparePortrait(
                scriptId,
                file,
                sourceMode,
                timestampSeconds,
                Boolean.TRUE.equals(consentConfirmed),
                tenantId,
                userId
        ));
    }

    @PostMapping("/scripts/{scriptId}/founder-avatar-test")
    public ResponseEntity<Map<String, Object>> generateFounderAvatarTest(
            @PathVariable UUID scriptId,
            @RequestBody(required = false) Map<String, Object> request,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity.status(HttpStatus.CREATED).body(
                founderAvatarTestService.generateTest(scriptId, request, tenantId, userId)
        );
    }

    @PostMapping(value = "/scripts/{scriptId}/founder-final-audio", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> uploadFounderFinalAudio(
            @PathVariable UUID scriptId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "captionText", required = false) String captionText,
            @RequestParam(value = "consentConfirmed", required = false) Boolean consentConfirmed,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity.status(HttpStatus.CREATED).body(screenplayVideoService.uploadFounderFinalAudio(
                scriptId,
                file,
                captionText,
                Boolean.TRUE.equals(consentConfirmed),
                tenantId,
                userId
        ));
    }

    @GetMapping("/scripts/{scriptId}/videos/latest")
    public ResponseEntity<Map<String, Object>> getLatestScreenplayVideoRun(
            @PathVariable UUID scriptId,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity.ok(screenplayVideoService.getLatestVideoRun(scriptId, tenantId, userId));
    }

    @GetMapping("/scripts/{scriptId}/videos/{runId}")
    public ResponseEntity<Map<String, Object>> getScreenplayVideoRun(
            @PathVariable UUID scriptId,
            @PathVariable UUID runId,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity.ok(screenplayVideoService.getVideoRun(scriptId, runId, tenantId, userId));
    }

    @PostMapping("/videos/{runId}/scenes/{sceneId}/chat")
    public ResponseEntity<Map<String, Object>> chatScreenplayVideoScene(
            @PathVariable UUID runId,
            @PathVariable String sceneId,
            @RequestBody(required = false) Map<String, Object> request,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity.ok(screenplayVideoService.chatScene(runId, sceneId, request, tenantId, userId));
    }

    @PostMapping("/videos/{runId}/scenes/{sceneId}/dialogue-voice")
    public ResponseEntity<Map<String, Object>> generateScreenplaySceneDialogueVoice(
            @PathVariable UUID runId,
            @PathVariable String sceneId,
            @RequestBody(required = false) Map<String, Object> request,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity.ok(screenplayVideoService.generateSceneDialogueVoice(runId, sceneId, request, tenantId, userId));
    }

    @PostMapping("/videos/{runId}/scenes/{sceneId}/dialogue-voice/decision")
    public ResponseEntity<Map<String, Object>> decideScreenplaySceneDialogueVoice(
            @PathVariable UUID runId,
            @PathVariable String sceneId,
            @RequestBody(required = false) Map<String, Object> request,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity.ok(screenplayVideoService.decideSceneDialogueVoice(runId, sceneId, request, tenantId, userId));
    }

    @PostMapping("/videos/{runId}/dialogue-audio/combine")
    public ResponseEntity<Map<String, Object>> combineScreenplaySceneDialogueAudio(
            @PathVariable UUID runId,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity.ok(
                screenplayVideoService.combineSceneDialogueAudio(runId, tenantId, userId)
        );
    }

    @PostMapping(
            value = "/videos/{runId}/scenes/{sceneId}/avatar-image",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public ResponseEntity<Map<String, Object>> uploadScreenplaySceneAvatarImage(
            @PathVariable UUID runId,
            @PathVariable String sceneId,
            @RequestParam("file") MultipartFile file,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity.ok(screenplayVideoService.uploadSceneAvatarPortrait(runId, sceneId, file, tenantId, userId));
    }

    @PostMapping(
            value = "/videos/{runId}/scenes/{sceneId}/production-image",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public ResponseEntity<Map<String, Object>> uploadScreenplaySceneProductionImage(
            @PathVariable UUID runId,
            @PathVariable String sceneId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "details", required = false) String details,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity.ok(
                screenplayVideoService.uploadSceneProductionImage(runId, sceneId, file, details, tenantId, userId)
        );
    }

    @PostMapping("/videos/{runId}/scenes/{sceneId}/regenerate-async")
    public ResponseEntity<GenerationJobResponse> regenerateScreenplayVideoSceneAsync(
            @PathVariable UUID runId,
            @PathVariable String sceneId,
            @RequestBody(required = false) Map<String, Object> request,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity
                .status(HttpStatus.ACCEPTED)
                .body(generationJobService.toResponse(asyncScreenplayVideoService.startSceneRegeneration(runId, sceneId, request, tenantId, userId)));
    }

    @PostMapping("/videos/{runId}/scenes/{sceneId}/generate-async")
    public ResponseEntity<GenerationJobResponse> generateScreenplayVideoSceneAsync(
            @PathVariable UUID runId,
            @PathVariable String sceneId,
            @RequestBody(required = false) Map<String, Object> request,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity
                .status(HttpStatus.ACCEPTED)
                .body(generationJobService.toResponse(asyncScreenplayVideoService.startSceneRegeneration(runId, sceneId, request, tenantId, userId)));
    }

    @PostMapping("/videos/{runId}/final-render-async")
    public ResponseEntity<GenerationJobResponse> renderScreenplayFinalVideoAsync(
            @PathVariable UUID runId,
            @RequestBody(required = false) Map<String, Object> request,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity
                .status(HttpStatus.ACCEPTED)
                .body(generationJobService.toResponse(asyncScreenplayVideoService.startFinalRender(runId, request, tenantId, userId)));
    }

    @PostMapping("/videos/{runId}/audio-pack-async")
    public ResponseEntity<GenerationJobResponse> generateScreenplayAudioPackAsync(
            @PathVariable UUID runId,
            @RequestBody(required = false) Map<String, Object> request,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity
                .status(HttpStatus.ACCEPTED)
                .body(generationJobService.toResponse(asyncScreenplayVideoService.startAudioPack(runId, request, tenantId, userId)));
    }

    @PostMapping("/scripts/{scriptId}/shots/{shotNumber}/images/{imageKind}")
    public ResponseEntity<StoryboardSceneResponse> generateShotImage(
            @PathVariable UUID scriptId,
            @PathVariable Integer shotNumber,
            @PathVariable String imageKind,
            @Valid @RequestBody(required = false) GenerateStoryboardRequest request,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(storyboardService.generateShotImage(scriptId, shotNumber == null ? 1 : shotNumber, imageKind, request, tenantId, userId));
    }

    @PostMapping("/scripts/{scriptId}/shots/{shotNumber}/ai-edit")
    public ResponseEntity<StoryboardSceneResponse> editShotWithAi(
            @PathVariable UUID scriptId,
            @PathVariable Integer shotNumber,
            @Valid @RequestBody ShotAiEditRequest request,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(storyboardService.editShotWithAi(scriptId, shotNumber == null ? 1 : shotNumber, request, tenantId, userId));
    }

    @PostMapping("/scripts/{scriptId}/shots/insert")
    public ResponseEntity<StoryboardSceneResponse> insertTimelineShot(
            @PathVariable UUID scriptId,
            @Valid @RequestBody ShotTimelineInsertRequest request,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(storyboardService.insertTimelineShot(scriptId, request, tenantId, userId));
    }
}
