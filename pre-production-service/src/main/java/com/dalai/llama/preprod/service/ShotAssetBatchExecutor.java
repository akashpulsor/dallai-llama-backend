package com.dalai.llama.preprod.service;

import com.dalai.llama.joblifecycle.BatchStepExecutor;
import com.dalai.llama.preprod.domain.ShotImageKind;
import com.dalai.llama.preprod.domain.ShotType;
import com.dalai.llama.preprod.domain.entity.Shot;
import com.dalai.llama.preprod.repository.ShotRepository;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** The only pre-production-specific code a "Generate all shot assets" batch needs -- everything
 * else (the queue, the retry-then-dead-letter machinery, the single-worker scheduling) is
 * job-lifecycle-common's generic {@code BatchWorker}. Plans the fixed 6-step sequence (lighting
 * plan, camera plan, then all 4 image kinds) for every camera-planned shot in a project --
 * MOTION_GRAPHIC shots are excluded, same reasoning as the shot-list generation's own auto-wiring:
 * they already got their own plan then, and have no camera/lighting to speak of (see ShotType's
 * javadoc). */
@Component
public class ShotAssetBatchExecutor implements BatchStepExecutor<ShotAssetStep> {

    private final ShotRepository shotRepository;
    private final LightingPlanService lightingPlanService;
    private final CameraPlanService cameraPlanService;
    private final ShotImageService shotImageService;

    public ShotAssetBatchExecutor(
            ShotRepository shotRepository,
            LightingPlanService lightingPlanService,
            CameraPlanService cameraPlanService,
            ShotImageService shotImageService
    ) {
        this.shotRepository = shotRepository;
        this.lightingPlanService = lightingPlanService;
        this.cameraPlanService = cameraPlanService;
        this.shotImageService = shotImageService;
    }

    @Override
    public List<ShotAssetStep> planSteps(UUID tenantId, UUID projectId) {
        List<Shot> eligibleShots = shotRepository.findByProjectIdOrderByShotNumberAsc(projectId).stream()
                .filter(s -> s.getShotType() != ShotType.MOTION_GRAPHIC)
                .toList();
        int totalShots = eligibleShots.size();

        return eligibleShots.stream()
                .flatMap(shot -> Arrays.stream(ShotAssetStep.Kind.values())
                        .map(kind -> new ShotAssetStep(shot.getId(), shot.getShotNumber(), totalShots, kind)))
                .toList();
    }

    @Override
    public void execute(UUID tenantId, UUID projectId, ShotAssetStep step) throws Exception {
        switch (step.kind()) {
            case LIGHTING_PLAN -> lightingPlanService.generate(tenantId, step.shotId());
            case CAMERA_PLAN -> cameraPlanService.generate(tenantId, step.shotId());
            case STORYBOARD_IMAGE -> shotImageService.generate(tenantId, step.shotId(), ShotImageKind.STORYBOARD);
            case PRODUCTION_IMAGE -> shotImageService.generate(tenantId, step.shotId(), ShotImageKind.PRODUCTION);
            case LIGHTING_IMAGE -> shotImageService.generate(tenantId, step.shotId(), ShotImageKind.LIGHTING);
            case CAMERA_PLAN_IMAGE -> shotImageService.generate(tenantId, step.shotId(), ShotImageKind.CAMERA_PLAN);
        }
    }

    @Override
    public String label(ShotAssetStep step) {
        return "Shot " + step.shotNumber() + " of " + step.totalShots() + " -- " + kindLabel(step.kind());
    }

    @Override
    public String stepKey(ShotAssetStep step) {
        return step.shotId() + ":" + step.kind().name();
    }

    private String kindLabel(ShotAssetStep.Kind kind) {
        return switch (kind) {
            case LIGHTING_PLAN -> "lighting plan";
            case CAMERA_PLAN -> "camera plan";
            case STORYBOARD_IMAGE -> "storyboard image";
            case PRODUCTION_IMAGE -> "production image";
            case LIGHTING_IMAGE -> "lighting image";
            case CAMERA_PLAN_IMAGE -> "camera plan image";
        };
    }
}
