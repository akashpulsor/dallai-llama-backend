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
import com.dalai.llama.videogen.dto.FeatureFlags;
import com.dalai.llama.videogen.dto.FoleyCueView;
import com.dalai.llama.videogen.dto.GenerateShotRequest;
import com.dalai.llama.videogen.dto.GenerateShotResponse;
import com.dalai.llama.videogen.dto.RejectRequest;
import com.dalai.llama.videogen.dto.ShotPromptView;
import com.dalai.llama.videogen.dto.VideoGenJobView;
import com.dalai.llama.videogen.dto.shotcontext.Character;
import com.dalai.llama.videogen.dto.shotcontext.ShotContext;
import com.dalai.llama.videogen.repository.FoleyCueRepository;
import com.dalai.llama.videogen.repository.ShotPromptReferenceRepository;
import com.dalai.llama.videogen.repository.ShotPromptRepository;
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
    private final FoleyCueRepository foleyCueRepository;
    private final VideoAssetPersistenceService videoAssetPersistenceService;
    private final VideoGenJobPersistenceService jobPersistenceService;
    private final String defaultModel;
    private final int maxPromptLength;

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
            FoleyCueRepository foleyCueRepository,
            VideoAssetPersistenceService videoAssetPersistenceService,
            VideoGenJobPersistenceService jobPersistenceService,
            @Value("${video-gen.llm-gateway.default-video-model}") String defaultModel,
            @Value("${video-gen.provider.default-max-prompt-length:2000}") int maxPromptLength
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
        this.foleyCueRepository = foleyCueRepository;
        this.videoAssetPersistenceService = videoAssetPersistenceService;
        this.jobPersistenceService = jobPersistenceService;
        this.defaultModel = defaultModel;
        this.maxPromptLength = maxPromptLength;
    }

    public GenerateShotResponse generate(TenantContext tenantContext, GenerateShotRequest request) {
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

        BuiltPrompt builtPrompt = promptBuilderService.buildPrompt(shotContext, effectiveFlags);
        List<DerivedFoleyCue> cues = foleyCueService.deriveCues(shotContext);
        CompressionResult compression = promptCompressionService.compressIfNeeded(builtPrompt.positive(), maxPromptLength);
        CostEstimate estimate = costEstimationService.estimate(
                compression.compressionApplied() ? compression.compressedPrompt() : builtPrompt.positive(), modelId);

        VideoGenJob job = VideoGenJob.builder()
                .jobId(UUID.randomUUID())
                .tenantId(tenantId)
                .projectId(projectId)
                .createdBy(tenantContext.userId())
                .shotRef(shotContext.shotRef())
                .providerId(resolveProviderId(modelId))
                .modelId(modelId)
                .status(JobStatus.PENDING_APPROVAL)
                .approvalStatus(ApprovalStatus.PENDING)
                .estimatedCost(estimate.estimatedCost())
                .costCurrency(estimate.currency())
                .createdAt(OffsetDateTime.now())
                .build();
        videoGenJobRepository.save(job);

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
                .createdAt(OffsetDateTime.now())
                .build();
        shotPromptRepository.save(prompt);

        saveReferences(prompt.getPromptId(), shotContext);
        saveFoleyCues(prompt.getPromptId(), cues);

        boolean autoApprove = request.autoApprove() || projectConfigService.isAutoApprove(tenantId, projectId);
        VideoGenJobView jobView;
        if (autoApprove) {
            jobView = approve(tenantContext, job.getJobId());
        } else {
            jobView = toJobView(job);
        }

        return new GenerateShotResponse(
                job.getJobId(),
                prompt.getPromptId(),
                jobView.status(),
                effectiveFlags,
                estimate.estimatedCost(),
                recommendation != null ? recommendation.recommendedModel() : null,
                recommendation != null ? recommendation.reasoning() : null,
                toFoleyCueViews(cues)
        );
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
            VideoDispatchParams params = new VideoDispatchParams(null, null);
            DispatchResult result = videoGenDispatchService.dispatch(job, positive, prompt.getNegativePrompt(), params);
            // Copy the provider's own hosted result into our MinIO -- durable, and this is what
            // GET /v1/jobs/{id}/video (the UI-facing endpoint) actually serves.
            VideoAssetPersistenceService.PersistedAsset asset = videoAssetPersistenceService.persist(job.getJobId(), result.outputUri());
            job = jobPersistenceService.finishSuccess(
                    jobId,
                    result.llmGatewayJobId() == null ? null : result.llmGatewayJobId().toString(),
                    result.outputUri(), result.actualCost(), asset.bucket(), asset.objectKey());
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
                job.map(j -> j.getStatus().name()).orElse(null)
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
                    .slotIndex(slot)
                    .build());
        }
        if (!references.isEmpty()) {
            shotPromptReferenceRepository.saveAll(references);
        }
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
        Integer duration = shotContext.technical() != null ? shotContext.technical().durationSeconds() : null;
        String durationBucket = duration == null ? "MEDIUM" : duration <= 3 ? "SHORT" : duration <= 7 ? "MEDIUM" : "LONG";
        return new ShotSignature(hasFace, isMotionOnly, requiresLipSync, durationBucket, "DRAFT");
    }

    private String resolveProviderId(String modelId) {
        // v1: every registered video model is fal.ai-hosted (Seedance); multi-provider (doc §6)
        // would resolve this from llm-gateway's model catalog instead of a fixed default.
        return "fal.ai";
    }

    private VideoGenJobView toJobView(VideoGenJob job) {
        return new VideoGenJobView(
                job.getJobId(),
                job.getShotRef(),
                job.getStatus().name(),
                job.getApprovalStatus().name(),
                job.getOutputUri(),
                job.getEstimatedCost(),
                job.getActualCost()
        );
    }
}
