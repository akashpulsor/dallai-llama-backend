package com.dalai.llama.preprod.service;

import com.dalai.llama.joblifecycle.JobLifecycleStatus;
import com.dalai.llama.preprod.domain.ShotImageKind;
import com.dalai.llama.preprod.domain.ShotType;
import com.dalai.llama.preprod.domain.entity.Shot;
import com.dalai.llama.preprod.domain.entity.ShotAssetBatchJob;
import com.dalai.llama.preprod.domain.entity.ShotImage;
import com.dalai.llama.preprod.dto.ShotAssetBatchJobView;
import com.dalai.llama.preprod.dto.ShotAssetCompletionView;
import com.dalai.llama.preprod.repository.CameraPlanRepository;
import com.dalai.llama.preprod.repository.LightingPlanRepository;
import com.dalai.llama.preprod.repository.ShotAssetBatchJobRepository;
import com.dalai.llama.preprod.repository.ShotImageRepository;
import com.dalai.llama.preprod.repository.ShotRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Enqueues and reports on a "Generate all shot assets" run -- pure job submission, per its own
 * design goal: the actual work happens one step at a time in job-lifecycle-common's BatchWorker
 * (see {@link ShotAssetBatchWorker}), this is just the queue entry/exit point. Deliberately never
 * runs the work itself: doing so here would mean the HTTP request that calls {@link #start}
 * blocks for the whole batch, the exact problem this exists to avoid. The normal per-shot
 * "Plan"/"Regenerate" flow (LightingPlanService/CameraPlanService/ShotImageService called
 * directly from their own controllers) is entirely separate from this and untouched by it. */
@Service
public class ShotAssetBatchService {

    private final ShotAssetBatchJobRepository repository;
    private final ShotAssetBatchExecutor executor;
    private final ShotRepository shotRepository;
    private final LightingPlanRepository lightingPlanRepository;
    private final CameraPlanRepository cameraPlanRepository;
    private final ShotImageRepository shotImageRepository;

    public ShotAssetBatchService(
            ShotAssetBatchJobRepository repository,
            ShotAssetBatchExecutor executor,
            ShotRepository shotRepository,
            LightingPlanRepository lightingPlanRepository,
            CameraPlanRepository cameraPlanRepository,
            ShotImageRepository shotImageRepository
    ) {
        this.repository = repository;
        this.executor = executor;
        this.shotRepository = shotRepository;
        this.lightingPlanRepository = lightingPlanRepository;
        this.cameraPlanRepository = cameraPlanRepository;
        this.shotImageRepository = shotImageRepository;
    }

    /** Idempotent: a project with an already PENDING/PROCESSING run returns that job instead of
     * queueing a second one racing it for the same shots. */
    @Transactional
    public ShotAssetBatchJobView start(UUID tenantId, UUID projectId) {
        ShotAssetBatchJob existing = repository.findTopByProjectIdOrderByCreatedAtDesc(projectId).orElse(null);
        if (existing != null && !existing.getStatus().isTerminal()) {
            return toView(existing);
        }

        int totalSteps = executor.planSteps(tenantId, projectId).size();
        if (totalSteps == 0) {
            throw PreProductionException.badRequest(
                    "Project " + projectId + " has no camera-planned shots to generate assets for -- generate the shot list first");
        }

        ShotAssetBatchJob job = repository.save(ShotAssetBatchJob.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .projectId(projectId)
                .status(JobLifecycleStatus.PENDING)
                .totalSteps(totalSteps)
                .createdAt(OffsetDateTime.now())
                .build());
        return toView(job);
    }

    @Transactional(readOnly = true)
    public ShotAssetBatchJobView status(UUID tenantId, UUID projectId) {
        ShotAssetBatchJob job = repository.findTopByProjectIdOrderByCreatedAtDesc(projectId)
                .filter(j -> j.getTenantId().equals(tenantId))
                .orElseThrow(() -> PreProductionException.notFound("No shot-asset batch has run for project " + projectId));
        return toView(job);
    }

    private static final Set<ShotImageKind> REQUIRED_IMAGE_KINDS = EnumSet.allOf(ShotImageKind.class);

    /** Drives the shots list's per-shot green check -- true only when every one of the 6 assets
     * this shot needs (lighting plan, camera plan, all 4 image kinds) actually exists, regardless
     * of how it got there (batch, manual "Plan"/"Generate", or a dead-letter retry -- this checks
     * real ground truth, not batch/dead-letter bookkeeping). MOTION_GRAPHIC shots are excluded,
     * same eligibility filter as {@link ShotAssetBatchExecutor#planSteps} -- they never need these
     * assets at all, so they'd otherwise always show as incomplete. */
    @Transactional(readOnly = true)
    public List<ShotAssetCompletionView> completion(UUID tenantId, UUID projectId) {
        List<Shot> eligibleShots = shotRepository.findByProjectIdOrderByShotNumberAsc(projectId).stream()
                .filter(s -> s.getShotType() != ShotType.MOTION_GRAPHIC)
                .toList();
        return eligibleShots.stream()
                .map(shot -> new ShotAssetCompletionView(shot.getId(), isComplete(shot.getId())))
                .collect(Collectors.toList());
    }

    private boolean isComplete(UUID shotId) {
        if (lightingPlanRepository.findByShotId(shotId).isEmpty()) {
            return false;
        }
        if (cameraPlanRepository.findByShotId(shotId).isEmpty()) {
            return false;
        }
        Set<ShotImageKind> presentKinds = shotImageRepository.findByShotId(shotId).stream()
                .map(ShotImage::getKind)
                .collect(Collectors.toSet());
        return presentKinds.containsAll(REQUIRED_IMAGE_KINDS);
    }

    /** 6 steps (lighting plan, camera plan, 4 images) per shot -- see ShotAssetStep.Kind. Views
     * still speak in "shots" (what a creator actually thinks in), while the job itself and
     * job-lifecycle-common's algorithm only ever deal in generic steps. */
    private static final int STEPS_PER_SHOT = ShotAssetStep.Kind.values().length;

    private ShotAssetBatchJobView toView(ShotAssetBatchJob job) {
        return new ShotAssetBatchJobView(job.getId(), job.getStatus().name(),
                job.getTotalSteps() / STEPS_PER_SHOT, job.getCompletedSteps() / STEPS_PER_SHOT,
                job.getErrorCount(), job.getCurrentStepLabel(), job.getLastError());
    }
}
