package com.dalai.llama.preprod.service;

import com.dalai.llama.joblifecycle.JobLifecycleStatus;
import com.dalai.llama.preprod.domain.EmotionalArcPosition;
import com.dalai.llama.preprod.domain.GenerationJobType;
import com.dalai.llama.preprod.domain.ShotStatus;
import com.dalai.llama.preprod.domain.entity.CastAssignment;
import com.dalai.llama.preprod.domain.entity.CastProfile;
import com.dalai.llama.preprod.domain.entity.GenerationJob;
import com.dalai.llama.preprod.domain.entity.Project;
import com.dalai.llama.preprod.domain.entity.ProjectConfig;
import com.dalai.llama.preprod.domain.entity.ScreenplayScene;
import com.dalai.llama.preprod.domain.entity.Script;
import com.dalai.llama.preprod.domain.entity.ScriptCharacter;
import com.dalai.llama.preprod.domain.entity.Shot;
import com.dalai.llama.preprod.dto.ShotDispatchResponse;
import com.dalai.llama.preprod.repository.CastAssignmentRepository;
import com.dalai.llama.preprod.repository.CastProfileRepository;
import com.dalai.llama.preprod.repository.GenerationJobRepository;
import com.dalai.llama.preprod.repository.ProjectConfigRepository;
import com.dalai.llama.preprod.repository.ProjectRepository;
import com.dalai.llama.preprod.repository.ScreenplaySceneRepository;
import com.dalai.llama.preprod.repository.ScriptCharacterRepository;
import com.dalai.llama.preprod.repository.ScriptRepository;
import com.dalai.llama.preprod.repository.ShotRepository;
import com.dalai.llama.preprod.service.assembly.EmotionalArcPositionCalculator;
import com.dalai.llama.preprod.service.assembly.ShotAssemblyContext;
import com.dalai.llama.preprod.service.assembly.ShotContextAssemblyStrategy;
import com.dalai.llama.preprod.service.assembly.ShotContextAssemblyStrategyResolver;
import com.dalai.llama.preprod.service.critic.CriticServiceClient;
import com.dalai.llama.preprod.service.critic.CritiqueRequest;
import com.dalai.llama.preprod.service.critic.CritiqueResult;
import com.dalai.llama.preprod.service.critic.CritiqueVerdict;
import com.dalai.llama.preprod.service.videogen.FeatureFlags;
import com.dalai.llama.preprod.service.videogen.GenerateShotRequest;
import com.dalai.llama.preprod.service.videogen.GenerateShotResponse;
import com.dalai.llama.preprod.service.videogen.VideoGenClient;
import com.dalai.llama.preprod.service.videogen.shotcontext.ShotContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Assembles a real {@link ShotContext} for one shot and dispatches it to
 * video-generation-service's own {@code POST /v1/shots/generate} -- the actual point of this
 * whole service's existence. Per-shot-type assembly is delegated to a
 * {@link ShotContextAssemblyStrategy} (see {@link ShotContextAssemblyStrategyResolver}); this
 * class owns loading the shared context and the {@link GenerationJob} lifecycle around the call.
 */
@Service
public class ShotContextAssemblyService {

    private final ShotRepository shotRepository;
    private final ProjectRepository projectRepository;
    private final ProjectConfigRepository projectConfigRepository;
    private final ScreenplaySceneRepository screenplaySceneRepository;
    private final CastAssignmentRepository castAssignmentRepository;
    private final CastProfileRepository castProfileRepository;
    private final ScriptRepository scriptRepository;
    private final ScriptCharacterRepository scriptCharacterRepository;
    private final GenerationJobRepository generationJobRepository;
    private final GenerationJobPersistenceService generationJobPersistenceService;
    private final ShotContextAssemblyStrategyResolver strategyResolver;
    private final VideoGenClient videoGenClient;
    private final CriticServiceClient criticServiceClient;
    private final GenerationThoughtService generationThoughtService;

    public ShotContextAssemblyService(
            ShotRepository shotRepository,
            ProjectRepository projectRepository,
            ProjectConfigRepository projectConfigRepository,
            ScreenplaySceneRepository screenplaySceneRepository,
            CastAssignmentRepository castAssignmentRepository,
            CastProfileRepository castProfileRepository,
            ScriptRepository scriptRepository,
            ScriptCharacterRepository scriptCharacterRepository,
            GenerationJobRepository generationJobRepository,
            GenerationJobPersistenceService generationJobPersistenceService,
            ShotContextAssemblyStrategyResolver strategyResolver,
            VideoGenClient videoGenClient,
            CriticServiceClient criticServiceClient,
            GenerationThoughtService generationThoughtService
    ) {
        this.shotRepository = shotRepository;
        this.projectRepository = projectRepository;
        this.projectConfigRepository = projectConfigRepository;
        this.screenplaySceneRepository = screenplaySceneRepository;
        this.castAssignmentRepository = castAssignmentRepository;
        this.castProfileRepository = castProfileRepository;
        this.scriptRepository = scriptRepository;
        this.scriptCharacterRepository = scriptCharacterRepository;
        this.generationJobRepository = generationJobRepository;
        this.generationJobPersistenceService = generationJobPersistenceService;
        this.strategyResolver = strategyResolver;
        this.videoGenClient = videoGenClient;
        this.criticServiceClient = criticServiceClient;
        this.generationThoughtService = generationThoughtService;
    }

    /**
     * Assemble -> pre-flight critique (mandatory, no bypass) -> dispatch. On {@code
     * NEEDS_HUMAN_REVIEW} the shot is left in {@link ShotStatus#NEEDS_REVIEW} and nothing is sent
     * to video-generation-service -- see critic-service's {@code CritiqueOrchestrator} for why
     * this is a bounded one-revision gate, not a retry loop.
     */
    @Transactional
    public ShotDispatchResponse dispatch(UUID tenantId, UUID shotId, boolean autoApprove) {
        Shot shot = shotRepository.findByIdAndTenantId(shotId, tenantId)
                .orElseThrow(() -> PreProductionException.notFound("No shot " + shotId));
        generationThoughtService.log(tenantId, shotId, "ASSEMBLING", "Assembling shot context for " + shot.getShotRef());
        ShotAssemblyContext assemblyContext = buildAssemblyContext(tenantId, shot);
        ShotContextAssemblyStrategy strategy = strategyResolver.resolve(shot.getShotType());
        ShotContext shotContext = strategy.assemble(assemblyContext);

        generationThoughtService.log(tenantId, shotId, "CRITIQUE_STARTED", "Running pre-flight critique on the assembled shot plan");
        CritiqueResult critique = criticServiceClient.critique(tenantId.toString(),
                new CritiqueRequest(shot.getProjectId(), shot.getId(), shotContext));

        if (critique.verdict() == CritiqueVerdict.NEEDS_HUMAN_REVIEW) {
            generationThoughtService.log(tenantId, shotId, "CRITIQUE_BLOCKED",
                    "Pre-flight critique found unresolved blocking issues -- dispatch withheld pending human review");
            shot.setStatus(ShotStatus.NEEDS_REVIEW);
            shot.setUpdatedAt(OffsetDateTime.now());
            shotRepository.save(shot);
            return new ShotDispatchResponse(null, null, null, null, critique.sessionId(), critique.verdict(), critique.findings(),
                    critique.decompositionRecommended(), critique.suggestedShotCount(), critique.decompositionReason());
        }
        if (critique.decompositionRecommended()) {
            generationThoughtService.log(tenantId, shotId, "DECOMPOSITION_RECOMMENDED",
                    "Pre-flight critique recommends splitting into " + critique.suggestedShotCount()
                            + " shots (dispatching the single-shot fallback plan anyway): " + critique.decompositionReason());
        }
        generationThoughtService.log(tenantId, shotId, "CRITIQUE_PASSED", "Pre-flight critique passed -- dispatching to video-generation-service");
        ShotContext dispatchPlan = critique.revisedPlan() != null ? critique.revisedPlan() : shotContext;

        OffsetDateTime now = OffsetDateTime.now();
        GenerationJob job = generationJobRepository.save(GenerationJob.builder()
                .tenantId(tenantId)
                .projectId(shot.getProjectId())
                .shotId(shot.getId())
                .jobType(GenerationJobType.SHOT_VIDEO_GENERATION)
                .status(JobLifecycleStatus.PENDING)
                .createdAt(now)
                .build());
        generationJobPersistenceService.markProcessing(job.getId());

        shot.setStatus(ShotStatus.GENERATING);
        shot.setUpdatedAt(now);
        shotRepository.save(shot);

        try {
            GenerateShotResponse response = videoGenClient.generateShot(tenantId.toString(),
                    new GenerateShotRequest(shot.getProjectId(), dispatchPlan, defaultFeatureFlags(), autoApprove));
            job = generationJobPersistenceService.finishSuccess(job.getId(), j -> {
                j.setExternalJobId(response.jobId());
                j.setExternalPromptId(response.promptId());
            });
            shot.setStatus(ShotStatus.GENERATED);
            shot.setUpdatedAt(OffsetDateTime.now());
            shotRepository.save(shot);
            generationThoughtService.log(tenantId, shotId, "SHOT_GENERATED",
                    "Shot generated -- video-generation-service job " + response.jobId());
            return new ShotDispatchResponse(job.getId(), job.getStatus(), job.getExternalJobId(), job.getExternalPromptId(),
                    critique.sessionId(), critique.verdict(), critique.findings(),
                    critique.decompositionRecommended(), critique.suggestedShotCount(), critique.decompositionReason());
        } catch (RuntimeException ex) {
            generationJobPersistenceService.finishFailure(job.getId(), ex.getMessage());
            shot.setStatus(ShotStatus.NEEDS_REGENERATION);
            shot.setUpdatedAt(OffsetDateTime.now());
            shotRepository.save(shot);
            generationThoughtService.log(tenantId, shotId, "SHOT_GENERATION_FAILED", "Dispatch failed: " + ex.getMessage());
            throw ex;
        }
    }

    /** Not the doc's full admin-editable feature_flag_definition catalog -- video-generation-service's
     * own code-level defaults apply when {@code null} is sent, same convention used everywhere
     * else feature flags come up this session. */
    private FeatureFlags defaultFeatureFlags() {
        return null;
    }

    private ShotAssemblyContext buildAssemblyContext(UUID tenantId, Shot shot) {
        Project project = projectRepository.findByIdAndTenantId(shot.getProjectId(), tenantId)
                .orElseThrow(() -> PreProductionException.notFound("No project " + shot.getProjectId()));
        ProjectConfig projectConfig = projectConfigRepository.findByProjectId(project.getId()).orElse(null);
        ScreenplayScene scene = screenplaySceneRepository.findById(shot.getScreenplaySceneId()).orElse(null);
        EmotionalArcPosition arcPosition = computeArcPosition(shot);

        CastAssignment castAssignment = null;
        CastProfile castProfile = null;
        if (shot.getPrimaryCharacterKey() != null) {
            Script script = scriptRepository.findByProjectId(project.getId()).orElse(null);
            ScriptCharacter character = script == null ? null
                    : scriptCharacterRepository.findByScriptIdAndCharacterKey(script.getId(), shot.getPrimaryCharacterKey()).orElse(null);
            if (character != null) {
                castAssignment = castAssignmentRepository.findByProjectIdAndScriptCharacterId(project.getId(), character.getId()).orElse(null);
            }
            if (castAssignment != null) {
                castProfile = castProfileRepository.findByIdAndTenantId(castAssignment.getCastProfileId(), tenantId).orElse(null);
            }
        }
        return new ShotAssemblyContext(shot, project, projectConfig, scene, arcPosition, castAssignment, castProfile);
    }

    private EmotionalArcPosition computeArcPosition(Shot shot) {
        List<Shot> allShots = shotRepository.findByProjectIdOrderByShotNumberAsc(shot.getProjectId());
        int index = 0;
        for (int i = 0; i < allShots.size(); i++) {
            if (allShots.get(i).getId().equals(shot.getId())) {
                index = i;
                break;
            }
        }
        return EmotionalArcPositionCalculator.fromOrdinal(index, Math.max(allShots.size(), 1));
    }
}
