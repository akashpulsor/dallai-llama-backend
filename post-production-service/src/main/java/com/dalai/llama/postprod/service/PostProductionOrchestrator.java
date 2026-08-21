package com.dalai.llama.postprod.service;

import com.dalai.llama.postprod.domain.PostProductionScope;
import com.dalai.llama.postprod.domain.PostProductionStatus;
import com.dalai.llama.postprod.domain.entity.DialogueSyncJob;
import com.dalai.llama.postprod.domain.entity.PostProductionJob;
import com.dalai.llama.postprod.dto.CreatePostProductionJobRequest;
import com.dalai.llama.postprod.dto.PostProductionJobView;
import com.dalai.llama.postprod.repository.PostProductionJobRepository;
import com.dalai.llama.postprod.service.videogen.VideoGenShotJob;
import com.dalai.llama.postprod.service.videogen.VideoGenerationClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Top-level entry point: scope=PROJECT runs post-production for every shot in the project that's
 * already COMPLETED in video-generation-service (shots not ready yet are simply skipped, not an
 * error -- lets a caller move a project over as soon as any shots are ready, not only once every
 * single one is); scope=SHOT runs a single named shot on its own, independent of its siblings.
 *
 * <p>Deliberately NOT @Transactional -- see PostProductionJobPersistenceService's class comment.
 */
@Service
public class PostProductionOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(PostProductionOrchestrator.class);

    private final VideoGenerationClient videoGenerationClient;
    private final PostProductionJobRepository postProductionJobRepository;
    private final PostProductionJobPersistenceService jobPersistenceService;
    private final DialogueSyncCoordinator dialogueSyncCoordinator;
    private final AssetPersistenceService assetPersistenceService;

    public PostProductionOrchestrator(
            VideoGenerationClient videoGenerationClient,
            PostProductionJobRepository postProductionJobRepository,
            PostProductionJobPersistenceService jobPersistenceService,
            DialogueSyncCoordinator dialogueSyncCoordinator,
            AssetPersistenceService assetPersistenceService
    ) {
        this.videoGenerationClient = videoGenerationClient;
        this.postProductionJobRepository = postProductionJobRepository;
        this.jobPersistenceService = jobPersistenceService;
        this.dialogueSyncCoordinator = dialogueSyncCoordinator;
        this.assetPersistenceService = assetPersistenceService;
    }

    public List<PostProductionJobView> createAndRun(UUID tenantId, UUID userId, CreatePostProductionJobRequest request) {
        List<VideoGenShotJob> candidates = videoGenerationClient.listJobsForProject(tenantId, request.projectId());

        List<VideoGenShotJob> targets = request.scope() == PostProductionScope.PROJECT
                ? candidates.stream().filter(VideoGenShotJob::isCompleted).toList()
                : candidates.stream()
                        .filter(j -> j.shotRef().equals(requireShotRef(request)))
                        .filter(VideoGenShotJob::isCompleted)
                        .toList();

        if (request.scope() == PostProductionScope.SHOT && targets.isEmpty()) {
            throw PostProductionException.badRequest(
                    "shot_ref=%s is not COMPLETED in video-generation-service yet".formatted(request.shotRef()));
        }

        return targets.stream()
                .map(shot -> runOne(tenantId, userId, request, shot))
                .toList();
    }

    private PostProductionJobView runOne(UUID tenantId, UUID userId, CreatePostProductionJobRequest request, VideoGenShotJob shot) {
        PostProductionJob job = PostProductionJob.builder()
                .jobId(UUID.randomUUID())
                .tenantId(tenantId)
                .projectId(request.projectId())
                .createdBy(userId)
                .shotRef(shot.shotRef())
                .scope(request.scope())
                .status(PostProductionStatus.PENDING)
                .videoGenJobId(shot.jobId())
                .createdAt(OffsetDateTime.now())
                .build();
        postProductionJobRepository.save(job);

        job = jobPersistenceService.markProcessing(job.getJobId());
        try {
            DialogueSyncJob dialogueSyncJob = dialogueSyncCoordinator.run(
                    tenantId, request.projectId(), job.getJobId(), shot.shotRef(), shot.jobId(), request.targetLanguage(),
                    request.voiceCloneModel(), request.ttsModel(), request.lipSyncModel());
            if (dialogueSyncJob.getStatus() == PostProductionStatus.COMPLETED) {
                job = jobPersistenceService.finishSuccess(
                        job.getJobId(), dialogueSyncJob.getDialogueSyncJobId(),
                        dialogueSyncJob.getOutputBucket(), dialogueSyncJob.getOutputObjectKey());
            } else {
                job = jobPersistenceService.finishFailure(job.getJobId(), dialogueSyncJob.getLastError());
            }
        } catch (RuntimeException ex) {
            log.warn("Post-production job failed jobId={} errorMessage={}", job.getJobId(), ex.getMessage());
            job = jobPersistenceService.finishFailure(job.getJobId(), ex.getMessage());
        }
        return toView(job);
    }

    private String requireShotRef(CreatePostProductionJobRequest request) {
        if (request.shotRef() == null || request.shotRef().isBlank()) {
            throw PostProductionException.badRequest("shot_ref is required when scope=SHOT");
        }
        return request.shotRef();
    }

    public List<PostProductionJobView> listForProject(UUID tenantId, UUID projectId) {
        return postProductionJobRepository.findByTenantIdAndProjectIdOrderByCreatedAtDesc(tenantId, projectId).stream()
                .map(this::toView)
                .toList();
    }

    public String getVideoUrl(UUID tenantId, UUID jobId) {
        PostProductionJob job = postProductionJobRepository.findById(jobId)
                .filter(j -> j.getTenantId().equals(tenantId))
                .orElseThrow(() -> PostProductionException.notFound("Unknown job_id: " + jobId));
        if (job.getOutputBucket() == null || job.getOutputObjectKey() == null) {
            throw PostProductionException.notFound(
                    "job_id=%s has no persisted video yet (status=%s)".formatted(jobId, job.getStatus()));
        }
        return assetPersistenceService.presignedUrl(job.getOutputBucket(), job.getOutputObjectKey());
    }

    private PostProductionJobView toView(PostProductionJob job) {
        return new PostProductionJobView(
                job.getJobId(), job.getProjectId(), job.getShotRef(), job.getScope().name(),
                job.getStatus().name(), job.getVideoGenJobId(), job.getLastError()
        );
    }
}
