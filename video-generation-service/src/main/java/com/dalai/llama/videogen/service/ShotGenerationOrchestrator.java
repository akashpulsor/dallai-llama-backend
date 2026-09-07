package com.dalai.llama.videogen.service;

import com.dalai.llama.videogen.domain.ApprovalStatus;
import com.dalai.llama.videogen.domain.FlagState;
import com.dalai.llama.videogen.domain.FoleyCueType;
import com.dalai.llama.videogen.domain.JobStatus;
import com.dalai.llama.videogen.domain.ReferenceKind;
import com.dalai.llama.videogen.domain.entity.FoleyCue;
import com.dalai.llama.videogen.domain.entity.ShotPrompt;
import com.dalai.llama.videogen.domain.entity.ShotPromptReference;
import com.dalai.llama.videogen.domain.entity.VideoGenJob;
import com.dalai.llama.videogen.domain.entity.VideoGenJobDialogueBeat;
import com.dalai.llama.videogen.dto.FeatureFlags;
import com.dalai.llama.videogen.dto.FoleyCueView;
import com.dalai.llama.videogen.dto.GenerateShotRequest;
import com.dalai.llama.videogen.dto.GenerateShotResponse;
import com.dalai.llama.videogen.dto.RejectRequest;
import com.dalai.llama.videogen.dto.ShotPromptView;
import com.dalai.llama.videogen.dto.VideoGenJobView;
import com.dalai.llama.videogen.dto.shotcontext.Character;
import com.dalai.llama.videogen.dto.shotcontext.DialogueBeat;
import com.dalai.llama.videogen.dto.shotcontext.ShotContext;
import com.dalai.llama.videogen.repository.FoleyCueRepository;
import com.dalai.llama.videogen.repository.ShotPromptReferenceRepository;
import com.dalai.llama.videogen.repository.ShotPromptRepository;
import com.dalai.llama.videogen.repository.VideoGenJobDialogueBeatRepository;
import com.dalai.llama.videogen.repository.VideoGenJobRepository;
import com.dalai.llama.videogen.web.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
public class ShotGenerationOrchestrator {

    private final ModelRecommendationService modelRecommendationService;
    private final PromptBuilderService promptBuilderService;
    private final PromptCompressionService promptCompressionService;
    private final FoleyCueService foleyCueService;
    private final CostEstimationService costEstimationService;
    private final VideoGenDispatchService videoGenDispatchService;
    private final ProjectConfigService projectConfigService;
    private final VideoGenJobRepository videoGenJobRepository;
    private final ShotPromptRepository shotPromptRepository;
    private final ShotPromptReferenceRepository shotPromptReferenceRepository;
    private final VideoGenJobDialogueBeatRepository videoGenJobDialogueBeatRepository;
    private final FoleyCueRepository foleyCueRepository;
    private final VideoAssetPersistenceService videoAssetPersistenceService;
    private final VideoGenJobPersistenceService jobPersistenceService;
    private final BeatDubbingService beatDubbingService;
    private final String defaultModel;

    public ShotGenerationOrchestrator(
            ModelRecommendationService modelRecommendationService,
            PromptBuilderService promptBuilderService,
            PromptCompressionService promptCompressionService,
            FoleyCueService foleyCueService,
            CostEstimationService costEstimationService,
            VideoGenDispatchService videoGenDispatchService,
            ProjectConfigService projectConfigService,
            VideoGenJobRepository videoGenJobRepository,
            ShotPromptRepository shotPromptRepository,
            ShotPromptReferenceRepository shotPromptReferenceRepository,
            VideoGenJobDialogueBeatRepository videoGenJobDialogueBeatRepository,
            FoleyCueRepository foleyCueRepository,
            VideoAssetPersistenceService videoAssetPersistenceService,
            VideoGenJobPersistenceService jobPersistenceService,
            BeatDubbingService beatDubbingService,
            @Value("${video-gen.llm-gateway.default-video-model}") String defaultModel
    ) {
        this.modelRecommendationService = modelRecommendationService;
        this.promptBuilderService = promptBuilderService;
        this.promptCompressionService = promptCompressionService;
        this.foleyCueService = foleyCueService;
        this.costEstimationService = costEstimationService;
        this.videoGenDispatchService = videoGenDispatchService;
        this.projectConfigService = projectConfigService;
        this.videoGenJobRepository = videoGenJobRepository;
        this.shotPromptRepository = shotPromptRepository;
        this.shotPromptReferenceRepository = shotPromptReferenceRepository;
        this.videoGenJobDialogueBeatRepository = videoGenJobDialogueBeatRepository;
        this.foleyCueRepository = foleyCueRepository;
        this.videoAssetPersistenceService = videoAssetPersistenceService;
        this.jobPersistenceService = jobPersistenceService;
        this.beatDubbingService = beatDubbingService;
        this.defaultModel = defaultModel;
    }

    public GenerateShotResponse generate(TenantContext tenantContext, GenerateShotRequest request) {
        PreparedShot prepared = prepareShot(tenantContext, request);
        boolean autoApprove = request.autoApprove()
                || projectConfigService.isAutoApprove(tenantContext.tenantId(), request.projectId());
        VideoGenJobView jobView = autoApprove ? approve(tenantContext, prepared.job().getJobId()) : toJobView(prepared.job());
        return new GenerateShotResponse(
                prepared.job().getJobId(),
                prepared.prompt().getPromptId(),
                jobView.status(),
                prepared.effectiveFlags(),
                prepared.estimatedCost(),
                prepared.recommendedModel(),
                prepared.recommendationReasoning(),
                toFoleyCueViews(prepared.foleyCues())
        );
    }

    /** Everything {@code generate()} did except the auto-approve/dispatch step -- extracted for
     * the prepare-scene flow ({@code PrepareSceneController.POST /v1/scenes/.../prepare}) which
     * needs the same assembled/built/persisted result but stops here so the user can review and
     * edit the prompt before dispatch. {@code generate()} then wraps this with the same
     * auto-approve-then-approve dispatch step it always did -- the external {@code POST
     * /v1/shots/generate} contract is unchanged. */
    public PreparedShot prepareShot(TenantContext tenantContext, GenerateShotRequest request) {
        return prepareShot(tenantContext, request, null);
    }

    /** Full form: {@code sources} carries the pre-prod row ids that fed this composed prompt.
     * Persisted on {@code ShotPrompt} so a debugger can walk back to the exact source-of-truth
     * rows (see {@code shot_prompt}'s source-id columns, V20 migration). Null when the caller
     * doesn't know the ids -- the row still saves, just without the audit pointers. */
    public PreparedShot prepareShot(TenantContext tenantContext, GenerateShotRequest request,
                                    ShotContextAssemblyService.ShotPromptSources sources) {
        UUID tenantId = tenantContext.tenantId();
        UUID projectId = request.projectId();
        ShotContext shotContext = request.shotContext();

        FeatureFlags effectiveFlags = projectConfigService.getEffectiveFlags(tenantId, projectId)
                .withOverride(request.featureFlagOverrides());

        String pinnedModel = shotContext.technical() != null ? shotContext.technical().targetModel() : null;
        ModelRecommendation recommendation = null;
        String modelId = pinnedModel;
        if (modelId == null || modelId.isBlank()) {
            recommendation = modelRecommendationService.recommend(deriveShotSignature(shotContext));
            modelId = recommendation != null && recommendation.recommendedModel() != null
                    ? recommendation.recommendedModel()
                    : defaultModel;
        }

        // modelId is now resolved -- pass it through so the strategy resolver picks the right
        // per-model composition shape and prompt-length limit, instead of the pre-refactor single
        // global default.
        BuiltPrompt builtPrompt = promptBuilderService.buildPrompt(shotContext, effectiveFlags, modelId);
        List<DerivedFoleyCue> cues = foleyCueService.deriveCues(shotContext);
        CompressionResult compression = promptCompressionService.compressIfNeeded(
                builtPrompt.positive(), promptBuilderService.maxPromptLengthFor(modelId));
        CostEstimate estimate = costEstimationService.estimate(
                compression.compressionApplied() ? compression.compressedPrompt() : builtPrompt.positive(), modelId);

        boolean muteAudio = beatDubbingService.canAutoDub(shotContext.dialogueBeats());

        VideoGenJob job = VideoGenJob.builder()
                .jobId(UUID.randomUUID())
                .tenantId(tenantId)
                .projectId(projectId)
                .createdBy(tenantContext.userId())
                .shotRef(shotContext.shotRef())
                .providerId(resolveProviderId(modelId))
                .modelId(modelId)
                .durationSeconds(shotContext.technical() != null ? shotContext.technical().durationSeconds() : null)
                .aspectRatio(shotContext.technical() != null && shotContext.technical().aspectRatio() != null
                        ? shotContext.technical().aspectRatio().wireValue() : null)
                .resolution(shotContext.technical() != null && shotContext.technical().resolution() != null
                        ? shotContext.technical().resolution().wireValue() : null)
                .voiceCloneModel(shotContext.technical() != null ? shotContext.technical().voiceCloneModel() : null)
                .ttsModel(shotContext.technical() != null ? shotContext.technical().ttsModel() : null)
                .muteAudio(muteAudio)
                .status(JobStatus.PENDING_APPROVAL)
                .approvalStatus(ApprovalStatus.PENDING)
                .estimatedCost(estimate.estimatedCost())
                .costCurrency(estimate.currency())
                .createdAt(OffsetDateTime.now())
                .build();
        videoGenJobRepository.save(job);
        if (muteAudio) {
            saveDialogueBeats(job.getJobId(), shotContext.dialogueBeats());
        }

        ShotPrompt prompt = ShotPrompt.builder()
                .promptId(UUID.randomUUID())
                .jobId(job.getJobId())
                .tenantId(tenantId)
                .projectId(projectId)
                .createdBy(tenantContext.userId())
                .promptOriginal(builtPrompt.positive())
                .promptCompressed(compression.compressionApplied() ? compression.compressedPrompt() : null)
                .compressionApplied(compression.compressionApplied())
                .originalLength(compression.originalLength())
                .compressedLength(compression.compressedLength())
                .namedEntitiesValidated(compression.namedEntitiesValidated())
                .negativePrompt(builtPrompt.negative())
                .dialogueFlag(effectiveFlags.dialogue())
                .captionsFlag(effectiveFlags.captions())
                .shipped(false)
                .shotId(sources == null ? null : sources.shotId())
                .cameraPlanId(sources == null ? null : sources.cameraPlanId())
                .lightingPlanId(sources == null ? null : sources.lightingPlanId())
                .productReferenceId(sources == null ? null : sources.productReferenceId())
                .backgroundMusicId(sources == null ? null : sources.backgroundMusicId())
                .recommendedModelId(recommendation == null ? null : recommendation.recommendedModel())
                .promptBundleSnapshotAt(sources == null ? null : sources.bundleSnapshotAt())
                .createdAt(OffsetDateTime.now())
                .build();
        shotPromptRepository.save(prompt);

        saveReferences(prompt.getPromptId(), shotContext);
        saveFoleyCues(prompt.getPromptId(), cues);

        return new PreparedShot(
                job,
                prompt,
                effectiveFlags,
                estimate.estimatedCost(),
                cues,
                recommendation != null ? recommendation.recommendedModel() : null,
                recommendation != null ? recommendation.reasoning() : null
        );
    }

    /** Result of the assembly/build/persist stage extracted from {@code generate()}. */
    public record PreparedShot(
            VideoGenJob job,
            ShotPrompt prompt,
            FeatureFlags effectiveFlags,
            BigDecimal estimatedCost,
            List<DerivedFoleyCue> foleyCues,
            String recommendedModel,
            String recommendationReasoning
    ) {}

    /** User edited a prepared prompt -- save a NEW ShotPrompt row with parent_prompt_id pointing
     * back at {@code promptId}, everything else copied. Uses ShotPrompt's existing versioning
     * columns; {@code listPromptsForShot} already returns newest-first, so history is preserved. */
    public ShotPromptView saveEditedPrompt(UUID tenantId, UUID promptId, String editedPositive) {
        ShotPrompt parent = shotPromptRepository.findById(promptId)
                .filter(p -> p.getTenantId().equals(tenantId))
                .orElseThrow(() -> VideoGenException.notFound("Unknown prompt_id: " + promptId));
        if (Boolean.TRUE.equals(parent.getShipped())) {
            throw VideoGenException.conflict("Prompt " + promptId + " has already shipped; editing is no longer permitted");
        }
        ShotPrompt edited = ShotPrompt.builder()
                .promptId(UUID.randomUUID())
                .jobId(parent.getJobId())
                .tenantId(parent.getTenantId())
                .projectId(parent.getProjectId())
                .createdBy(parent.getCreatedBy())
                .parentPromptId(parent.getPromptId())
                .variantLabel("user-edit")
                .promptOriginal(editedPositive)
                .compressionApplied(false)
                .originalLength(editedPositive == null ? 0 : editedPositive.length())
                .compressedLength(null)
                .namedEntitiesValidated(null)
                .negativePrompt(parent.getNegativePrompt())
                .dialogueFlag(parent.getDialogueFlag())
                .captionsFlag(parent.getCaptionsFlag())
                .shipped(false)
                .createdAt(OffsetDateTime.now())
                .build();
        shotPromptRepository.save(edited);
        return toPromptView(edited);
    }

    /** Doc §4.3: runs the full generate() pipeline once per shot, using project-level flag
     * defaults + auto_approve for every shot in the batch unless a per-batch override is set --
     * the "auto" mode: set flags globally once, then run the complete pipeline hands-off. */
    public com.dalai.llama.videogen.dto.GenerateBatchResponse generateBatch(
            TenantContext tenantContext, UUID projectId, com.dalai.llama.videogen.dto.GenerateBatchRequest request
    ) {
        List<GenerateShotResponse> results = request.shots().stream()
                .map(shot -> generate(tenantContext, new GenerateShotRequest(projectId, shot, request.featureFlagOverrides(), false)))
                .toList();
        return new com.dalai.llama.videogen.dto.GenerateBatchResponse(results);
    }

    /** Deliberately NOT @Transactional -- see {@link VideoGenJobPersistenceService}'s class
     * comment. dispatch() and the MinIO persist below are blocking external I/O that can run for
     * minutes; wrapping this whole method in one transaction would hold a pooled DB connection
     * for that entire window, leaving the PROCESSING write uncommitted (invisible to any
     * concurrent GET) until the very end, and risking pool exhaustion under a few concurrent
     * approvals. Each state transition below commits on its own, immediately. */
    public VideoGenJobView approve(TenantContext tenantContext, UUID jobId) {
        VideoGenJob job = requireJob(tenantContext.tenantId(), jobId);
        if (job.getStatus().isTerminal()) {
            throw VideoGenException.conflict("Cannot approve job_id=%s, already %s".formatted(jobId, job.getStatus()));
        }
        job = jobPersistenceService.markProcessing(jobId);

        ShotPrompt prompt = shotPromptRepository.findByJobIdOrderByCreatedAtDesc(jobId).stream()
                .findFirst()
                .orElseThrow(() -> VideoGenException.notFound("No shot_prompt for job_id=" + jobId));

        try {
            String positive = prompt.getCompressionApplied() ? prompt.getPromptCompressed() : prompt.getPromptOriginal();
            List<String> referenceImageUrls = resolveReferenceImageUrls(prompt.getPromptId());
            long seed = deriveSeed(job.getProjectId());
            job.setSeedUsed(seed);
            videoGenJobRepository.save(job);
            VideoDispatchParams params = new VideoDispatchParams(job.getDurationSeconds(), job.getAspectRatio(),
                    job.isMuteAudio() ? Boolean.FALSE : null, referenceImageUrls, seed, job.getResolution());
            DispatchResult result = videoGenDispatchService.dispatch(job, positive, prompt.getNegativePrompt(), params);

            String outputUri = result.outputUri();
            BigDecimal actualCost = result.actualCost();
            if (job.isMuteAudio()) {
                // Auto-dub: mux a beat-matched cloned-voice track onto the silent video Seedance
                // just returned -- see BeatDubbingService's class comment for why this is a
                // best-effort bet, not a guarantee, and what the fallback is when it misses.
                List<DialogueBeat> beats = loadDialogueBeats(job.getJobId());
                boolean dubSucceeded;
                try {
                    BeatDubbingService.DubResult dub = beatDubbingService.dub(
                            job.getTenantId().toString(), job.getJobId(), job.getProjectId(), beats, outputUri,
                            job.getVoiceCloneModel(), job.getTtsModel());
                    outputUri = dub.finalVideoUrl();
                    actualCost = actualCost.add(dub.cost());
                    dubSucceeded = true;
                } catch (RuntimeException dubEx) {
                    // Silent video still exists and is still usable -- a failed auto-dub degrades
                    // to "no dialogue audio" rather than failing the whole job. Post-production's
                    // fallback path is for sync QUALITY, not for recovering a failed dub call.
                    log.warn("Auto-dub failed jobId={} errorMessage={} -- keeping the silent video", jobId, dubEx.getMessage());
                    dubSucceeded = false;
                }
                // Direct save, not through jobPersistenceService -- finishSuccess() below re-fetches
                // the job fresh by id rather than reusing this instance, so dubSucceeded has to be
                // committed before that call for it to still be there afterward.
                job.setDubSucceeded(dubSucceeded);
                videoGenJobRepository.save(job);
            }

            // Copy the provider's own hosted result into our MinIO -- durable, and this is what
            // GET /v1/jobs/{id}/video (the UI-facing endpoint) actually serves.
            VideoAssetPersistenceService.PersistedAsset asset = videoAssetPersistenceService.persist(job.getJobId(), outputUri);
            job = jobPersistenceService.finishSuccess(
                    jobId,
                    result.llmGatewayJobId() == null ? null : result.llmGatewayJobId().toString(),
                    outputUri, actualCost, asset.bucket(), asset.objectKey());
        } catch (RuntimeException ex) {
            log.warn("Dispatch failed jobId={} errorMessage={}", jobId, ex.getMessage());
            job = jobPersistenceService.finishFailure(jobId, ex.getMessage());
        }
        return toJobView(job);
    }

    public VideoGenJobView reject(TenantContext tenantContext, UUID jobId, RejectRequest request) {
        VideoGenJob job = requireJob(tenantContext.tenantId(), jobId);
        if (job.getStatus().isTerminal()) {
            throw VideoGenException.conflict("Cannot reject job_id=%s, already %s".formatted(jobId, job.getStatus()));
        }
        job.setApprovalStatus(ApprovalStatus.REJECTED);
        job.setStatus(JobStatus.REJECTED);
        job.setLastError(request == null ? null : request.reason());
        job.setCompletedAt(OffsetDateTime.now());
        videoGenJobRepository.save(job);
        return toJobView(job);
    }

    public VideoGenJobView cancel(TenantContext tenantContext, UUID jobId) {
        VideoGenJob job = requireJob(tenantContext.tenantId(), jobId);
        if (job.getStatus().isTerminal()) {
            throw VideoGenException.conflict("Cannot cancel job_id=%s, already %s".formatted(jobId, job.getStatus()));
        }
        videoGenDispatchService.cancel(job);
        job.setStatus(JobStatus.CANCELLED);
        job.setCompletedAt(OffsetDateTime.now());
        videoGenJobRepository.save(job);
        return toJobView(job);
    }

    public ShotPromptView getPrompt(UUID tenantId, UUID promptId) {
        ShotPrompt prompt = shotPromptRepository.findById(promptId)
                .filter(p -> p.getTenantId().equals(tenantId))
                .orElseThrow(() -> VideoGenException.notFound("Unknown prompt_id: " + promptId));
        return toPromptView(prompt);
    }

    public List<ShotPromptView> listPromptsForShot(UUID tenantId, String shotRef) {
        return videoGenJobRepository.findByTenantIdAndShotRefOrderByCreatedAtDesc(tenantId, shotRef).stream()
                .flatMap(job -> shotPromptRepository.findByJobIdOrderByCreatedAtDesc(job.getJobId()).stream())
                .map(this::toPromptView)
                .toList();
    }

    /** Post-production-service's PROJECT-scope entry point ("move the whole finished video to
     * post-production") needs to discover every shot in a project without the caller having to
     * already know the full shot_ref list -- this returns the latest job per shot_ref, letting
     * the caller see at a glance which shots are COMPLETED (ready) vs. still pending/failed. */
    public List<VideoGenJobView> listJobsForProject(UUID tenantId, UUID projectId) {
        List<VideoGenJob> jobs = videoGenJobRepository.findByTenantIdAndProjectIdOrderByCreatedAtDesc(tenantId, projectId);
        java.util.LinkedHashMap<String, VideoGenJob> latestByShotRef = new java.util.LinkedHashMap<>();
        for (VideoGenJob job : jobs) {
            latestByShotRef.putIfAbsent(job.getShotRef(), job);
        }
        return latestByShotRef.values().stream().map(this::toJobView).toList();
    }

    private ShotPromptView toPromptView(ShotPrompt prompt) {
        List<FoleyCueView> cues = foleyCueRepository.findByPromptIdOrderByTimestampMsAsc(prompt.getPromptId()).stream()
                .map(c -> new FoleyCueView(c.getTimestampMs(), c.getCueType().name(), c.getDescription()))
                .toList();
        // Best-effort: a prompt row always has a job_id, but tolerate a missing job rather than
        // failing the whole list if one row is ever orphaned.
        var job = videoGenJobRepository.findById(prompt.getJobId());
        return new ShotPromptView(
                prompt.getPromptId(),
                prompt.getJobId(),
                prompt.getPromptOriginal(),
                prompt.getPromptCompressed(),
                prompt.getNegativePrompt(),
                new FeatureFlags(prompt.getDialogueFlag(), prompt.getCaptionsFlag()),
                Boolean.TRUE.equals(prompt.getShipped()),
                prompt.getParentPromptId(),
                cues,
                job.map(j -> j.getApprovalStatus().name()).orElse(null),
                job.map(j -> j.getStatus().name()).orElse(null),
                resolveReferenceImageUrls(prompt.getPromptId())
        );
    }

    /** The UI-facing endpoint's backing call: a short-lived signed MinIO URL for the shot the
     * caller can hand straight to a &lt;video&gt; tag. */
    public String getVideoUrl(UUID tenantId, UUID jobId) {
        VideoGenJob job = requireJob(tenantId, jobId);
        if (job.getOutputBucket() == null || job.getOutputObjectKey() == null) {
            throw VideoGenException.notFound(
                    "job_id=%s has no persisted video yet (status=%s)".formatted(jobId, job.getStatus()));
        }
        return videoAssetPersistenceService.presignedUrl(job.getOutputBucket(), job.getOutputObjectKey());
    }

    private VideoGenJob requireJob(UUID tenantId, UUID jobId) {
        return videoGenJobRepository.findById(jobId)
                .filter(j -> j.getTenantId().equals(tenantId))
                .orElseThrow(() -> VideoGenException.notFound("Unknown job_id: " + jobId));
    }

    private void saveReferences(UUID promptId, ShotContext shotContext) {
        List<ShotPromptReference> references = new ArrayList<>();
        int slot = 0;
        if (shotContext.characters() != null) {
            for (Character character : shotContext.characters()) {
                if (character.faceRefBucket() != null && character.faceRefObjectKey() != null) {
                    references.add(ShotPromptReference.builder()
                            .promptId(promptId)
                            .refKind(ReferenceKind.CHARACTER_FACE)
                            .bucket(character.faceRefBucket())
                            .objectKey(character.faceRefObjectKey())
                            .slotIndex(slot++)
                            .build());
                }
                // Voice reference is a persisted asset the downstream mux step (BeatDubbingService)
                // reads from ShotPromptReference to attach to the fal.ai/ElevenLabs voice-clone
                // call -- kept alongside face references so a prompt's full set of "what conditions
                // this character" attachments lives in one place.
                if (character.voiceRefBucket() != null && character.voiceRefObjectKey() != null) {
                    references.add(ShotPromptReference.builder()
                            .promptId(promptId)
                            .refKind(ReferenceKind.CHARACTER_VOICE)
                            .bucket(character.voiceRefBucket())
                            .objectKey(character.voiceRefObjectKey())
                            .slotIndex(slot++)
                            .build());
                }
            }
        }
        if (shotContext.productBrand() != null
                && shotContext.productBrand().productRefBucket() != null
                && shotContext.productBrand().productRefObjectKey() != null) {
            references.add(ShotPromptReference.builder()
                    .promptId(promptId)
                    .refKind(ReferenceKind.PRODUCT_HERO)
                    .bucket(shotContext.productBrand().productRefBucket())
                    .objectKey(shotContext.productBrand().productRefObjectKey())
                    .slotIndex(slot++)
                    .build());
        }
        if (shotContext.lighting() != null
                && shotContext.lighting().dpLightingImageBucket() != null
                && shotContext.lighting().dpLightingImageObjectKey() != null) {
            references.add(ShotPromptReference.builder()
                    .promptId(promptId)
                    .refKind(ReferenceKind.DP_LIGHTING)
                    .bucket(shotContext.lighting().dpLightingImageBucket())
                    .objectKey(shotContext.lighting().dpLightingImageObjectKey())
                    .slotIndex(slot++)
                    .build());
        }
        if (shotContext.camera() != null
                && shotContext.camera().cameraPlanImageBucket() != null
                && shotContext.camera().cameraPlanImageObjectKey() != null) {
            references.add(ShotPromptReference.builder()
                    .promptId(promptId)
                    .refKind(ReferenceKind.CAMERA_PLAN_IMAGE)
                    .bucket(shotContext.camera().cameraPlanImageBucket())
                    .objectKey(shotContext.camera().cameraPlanImageObjectKey())
                    .slotIndex(slot++)
                    .build());
        }
        if (shotContext.audioAmbience() != null
                && shotContext.audioAmbience().backgroundMusicBucket() != null
                && shotContext.audioAmbience().backgroundMusicObjectKey() != null) {
            references.add(ShotPromptReference.builder()
                    .promptId(promptId)
                    .refKind(ReferenceKind.BACKGROUND_MUSIC)
                    .bucket(shotContext.audioAmbience().backgroundMusicBucket())
                    .objectKey(shotContext.audioAmbience().backgroundMusicObjectKey())
                    .slotIndex(slot++)
                    .build());
        }
        if (!references.isEmpty()) {
            shotPromptReferenceRepository.saveAll(references);
        }
    }

    /** Turns this prompt's saved {@code shot_prompt_reference} rows (character face / product
     * hero / DP-lighting / camera-plan images) into signed URLs for llm-gateway's
     * {@code reference_image_urls} -- ordered by {@code slotIndex} so the same ordering
     * {@link #saveReferences} used is preserved at dispatch time. Non-image kinds
     * ({@code CHARACTER_VOICE}, {@code BACKGROUND_MUSIC}) are deliberately excluded here -- they
     * live in the same {@code shot_prompt_reference} table for one-place bookkeeping but the
     * video model's reference-image slots must never receive an audio URL. Empty (not null)
     * when a prompt has no image references, so callers can pass it straight through without a
     * null check. */
    private List<String> resolveReferenceImageUrls(UUID promptId) {
        return shotPromptReferenceRepository.findByPromptId(promptId).stream()
                .filter(ref -> isImageReference(ref.getRefKind()))
                .sorted(java.util.Comparator.comparing(ShotPromptReference::getSlotIndex))
                .map(ref -> videoAssetPersistenceService.presignedUrl(ref.getBucket(), ref.getObjectKey()))
                .collect(java.util.stream.Collectors.toList());
    }

    private static boolean isImageReference(ReferenceKind kind) {
        return kind == ReferenceKind.CHARACTER_FACE
                || kind == ReferenceKind.SET
                || kind == ReferenceKind.STORYBOARD
                || kind == ReferenceKind.STYLE_ANCHOR
                || kind == ReferenceKind.PRIOR_SHOT_LAST_FRAME
                || kind == ReferenceKind.PRODUCT_HERO
                || kind == ReferenceKind.DP_LIGHTING
                || kind == ReferenceKind.CAMERA_PLAN_IMAGE;
    }

    private void saveFoleyCues(UUID promptId, List<DerivedFoleyCue> cues) {
        if (cues.isEmpty()) {
            return;
        }
        List<FoleyCue> entities = cues.stream()
                .map(cue -> FoleyCue.builder()
                        .promptId(promptId)
                        .timestampMs(cue.timestampMs())
                        .cueType(parseCueType(cue.cueType()))
                        .description(cue.description())
                        .build())
                .toList();
        foleyCueRepository.saveAll(entities);
    }

    private void saveDialogueBeats(UUID jobId, List<DialogueBeat> beats) {
        OffsetDateTime now = OffsetDateTime.now();
        int[] index = {0};
        List<VideoGenJobDialogueBeat> entities = beats.stream()
                .map(b -> VideoGenJobDialogueBeat.builder()
                        .id(UUID.randomUUID())
                        .jobId(jobId)
                        .orderIndex(index[0]++)
                        .startSeconds(b.startSeconds())
                        .durationSeconds(b.durationSeconds())
                        .text(b.text())
                        .characterKey(b.characterKey())
                        .voiceReferenceUrl(b.voiceReferenceUrl())
                        .builtinVoiceId(b.builtinVoiceId())
                        .emotion(b.emotion())
                        .createdAt(now)
                        .build())
                .toList();
        videoGenJobDialogueBeatRepository.saveAll(entities);
    }

    private List<DialogueBeat> loadDialogueBeats(UUID jobId) {
        return videoGenJobDialogueBeatRepository.findByJobIdOrderByOrderIndexAsc(jobId).stream()
                .map(b -> new DialogueBeat(b.getStartSeconds(), b.getDurationSeconds(), b.getText(), b.getCharacterKey(),
                        b.getVoiceReferenceUrl(), b.getBuiltinVoiceId(), b.getEmotion()))
                .toList();
    }

    private FoleyCueType parseCueType(String value) {
        try {
            return FoleyCueType.valueOf(value);
        } catch (Exception ex) {
            return FoleyCueType.AMBIENT_BED;
        }
    }

    private List<FoleyCueView> toFoleyCueViews(List<DerivedFoleyCue> cues) {
        return cues.stream()
                .map(c -> new FoleyCueView(c.timestampMs(), c.cueType(), c.description()))
                .toList();
    }

    private ShotSignature deriveShotSignature(ShotContext shotContext) {
        boolean hasFace = shotContext.characters() != null && shotContext.characters().stream()
                .anyMatch(c -> c.faceRefBucket() != null);
        boolean isMotionOnly = (shotContext.characters() == null || shotContext.characters().isEmpty());
        boolean requiresLipSync = hasFace && shotContext.narrative() != null && shotContext.narrative().scriptLine() != null;
        boolean hasDialogueBeats = shotContext.dialogueBeats() != null && !shotContext.dialogueBeats().isEmpty();
        Integer duration = shotContext.technical() != null ? shotContext.technical().durationSeconds() : null;
        String durationBucket = duration == null ? "MEDIUM" : duration <= 3 ? "SHORT" : duration <= 7 ? "MEDIUM" : "LONG";
        return new ShotSignature(hasFace, isMotionOnly, requiresLipSync, hasDialogueBeats, durationBucket, "DRAFT");
    }

    private String resolveProviderId(String modelId) {
        // v1: every registered video model is fal.ai-hosted (Seedance); multi-provider (doc §6)
        // would resolve this from llm-gateway's model catalog instead of a fixed default.
        return "fal.ai";
    }

    /** Deterministic seed per project -- the primary cross-shot continuity lever (character faces,
     * set details, lighting). Derived from the project's UUID rather than a per-project column
     * because the UUID is already stable and available on every job row; per-project locked_idea_id
     * is functionally equivalent in the current schema (idea is locked once at project creation
     * and never unlocked), so an extra pre-prod fetch would return a value that maps to the same
     * project 1:1. Signed high bits of the most-significant 64 bits gives a positive long that
     * FalAiProvider clamps to fal.ai's 32-bit signed int seed range. */
    private long deriveSeed(UUID projectId) {
        return Math.abs(projectId.getMostSignificantBits());
    }

    private VideoGenJobView toJobView(VideoGenJob job) {
        return new VideoGenJobView(
                job.getJobId(),
                job.getShotRef(),
                job.getStatus().name(),
                job.getApprovalStatus().name(),
                job.getOutputUri(),
                job.getEstimatedCost(),
                job.getActualCost(),
                job.isMuteAudio(),
                job.getDubSucceeded()
        );
    }
}
