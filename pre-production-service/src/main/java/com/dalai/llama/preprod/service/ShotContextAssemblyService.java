package com.dalai.llama.preprod.service;

import com.dalai.llama.joblifecycle.JobLifecycleStatus;
import com.dalai.llama.preprod.domain.CharacterType;
import com.dalai.llama.preprod.domain.EmotionalArcPosition;
import com.dalai.llama.preprod.domain.GenerationJobType;
import com.dalai.llama.preprod.domain.ShotStatus;
import com.dalai.llama.preprod.domain.entity.CastAssignment;
import com.dalai.llama.preprod.domain.entity.CastProfile;
import com.dalai.llama.preprod.domain.entity.ContinuityLock;
import com.dalai.llama.preprod.domain.entity.GenerationJob;
import com.dalai.llama.preprod.domain.entity.Project;
import com.dalai.llama.preprod.domain.entity.ProjectConfig;
import com.dalai.llama.preprod.domain.entity.ScreenplayScene;
import com.dalai.llama.preprod.domain.entity.Script;
import com.dalai.llama.preprod.domain.entity.ScriptCharacter;
import com.dalai.llama.preprod.domain.entity.Shot;
import com.dalai.llama.preprod.dto.ShotDispatchResponse;
import com.dalai.llama.preprod.domain.entity.ShotDialogueBeat;
import com.dalai.llama.preprod.repository.CastAssignmentRepository;
import com.dalai.llama.preprod.repository.CastProfileRepository;
import com.dalai.llama.preprod.repository.GenerationJobRepository;
import com.dalai.llama.preprod.repository.ProjectConfigRepository;
import com.dalai.llama.preprod.repository.ProjectRepository;
import com.dalai.llama.preprod.repository.ScreenplaySceneRepository;
import com.dalai.llama.preprod.repository.ScriptCharacterRepository;
import com.dalai.llama.preprod.repository.ScriptRepository;
import com.dalai.llama.preprod.repository.ShotDialogueBeatRepository;
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
import com.dalai.llama.preprod.service.videogen.FlagState;
import com.dalai.llama.preprod.service.videogen.GenerateShotRequest;
import com.dalai.llama.preprod.service.videogen.GenerateShotResponse;
import com.dalai.llama.preprod.service.videogen.VideoGenClient;
import com.dalai.llama.preprod.service.videogen.shotcontext.DialogueBeat;
import com.dalai.llama.preprod.service.videogen.shotcontext.ShotContext;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.http.Method;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

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
    private final ShotDialogueBeatRepository shotDialogueBeatRepository;
    private final GenerationJobRepository generationJobRepository;
    private final GenerationJobPersistenceService generationJobPersistenceService;
    private final ShotContextAssemblyStrategyResolver strategyResolver;
    private final VideoGenClient videoGenClient;
    private final CriticServiceClient criticServiceClient;
    private final GenerationThoughtService generationThoughtService;
    private final ContinuityBibleService continuityBibleService;
    private final MinioClient publicMinioClient;

    public ShotContextAssemblyService(
            ShotRepository shotRepository,
            ProjectRepository projectRepository,
            ProjectConfigRepository projectConfigRepository,
            ScreenplaySceneRepository screenplaySceneRepository,
            CastAssignmentRepository castAssignmentRepository,
            CastProfileRepository castProfileRepository,
            ScriptRepository scriptRepository,
            ScriptCharacterRepository scriptCharacterRepository,
            ShotDialogueBeatRepository shotDialogueBeatRepository,
            GenerationJobRepository generationJobRepository,
            GenerationJobPersistenceService generationJobPersistenceService,
            ShotContextAssemblyStrategyResolver strategyResolver,
            VideoGenClient videoGenClient,
            CriticServiceClient criticServiceClient,
            GenerationThoughtService generationThoughtService,
            ContinuityBibleService continuityBibleService,
            @Qualifier("publicMinioClient") MinioClient publicMinioClient
    ) {
        this.shotRepository = shotRepository;
        this.projectRepository = projectRepository;
        this.projectConfigRepository = projectConfigRepository;
        this.screenplaySceneRepository = screenplaySceneRepository;
        this.castAssignmentRepository = castAssignmentRepository;
        this.castProfileRepository = castProfileRepository;
        this.scriptRepository = scriptRepository;
        this.scriptCharacterRepository = scriptCharacterRepository;
        this.shotDialogueBeatRepository = shotDialogueBeatRepository;
        this.generationJobRepository = generationJobRepository;
        this.generationJobPersistenceService = generationJobPersistenceService;
        this.strategyResolver = strategyResolver;
        this.videoGenClient = videoGenClient;
        this.criticServiceClient = criticServiceClient;
        this.generationThoughtService = generationThoughtService;
        this.continuityBibleService = continuityBibleService;
        this.publicMinioClient = publicMinioClient;
    }

    /**
     * Assemble -> pre-flight critique (mandatory, no bypass) -> dispatch. On {@code
     * NEEDS_HUMAN_REVIEW} the shot is left in {@link ShotStatus#NEEDS_REVIEW} and nothing is sent
     * to video-generation-service -- see critic-service's {@code CritiqueOrchestrator} for why
     * this is a bounded one-revision gate, not a retry loop.
     */
    @Transactional
    public ShotDispatchResponse dispatch(UUID tenantId, UUID shotId, boolean autoApprove) {
        return dispatch(tenantId, shotId, autoApprove, null, null);
    }

    /** {@code dialogueOverride}/{@code captionsOverride} are per-call overrides of video-
     * generation-service's own dialogue/captions flags (see {@code VideoFeatureFlagDefinition}
     * for the master data a UI renders these choices from) -- null lets video-generation-service
     * apply the project's configured default instead. */
    @Transactional
    public ShotDispatchResponse dispatch(UUID tenantId, UUID shotId, boolean autoApprove, Boolean dialogueOverride, Boolean captionsOverride) {
        Shot shot = shotRepository.findByIdAndTenantId(shotId, tenantId)
                .orElseThrow(() -> PreProductionException.notFound("No shot " + shotId));
        generationThoughtService.log(tenantId, shotId, "ASSEMBLING", "Assembling shot context for " + shot.getShotRef());
        ShotAssemblyContext assemblyContext = buildAssemblyContext(tenantId, shot);
        ShotContextAssemblyStrategy strategy = strategyResolver.resolve(shot.getShotType());
        ShotContext shotContext = withDialogueBeats(strategy.assemble(assemblyContext), tenantId, shot, assemblyContext);

        generationThoughtService.log(tenantId, shotId, "CRITIQUE_STARTED", "Running pre-flight critique on the assembled shot plan");
        CritiqueResult critique = criticServiceClient.critique(tenantId.toString(),
                new CritiqueRequest(shot.getProjectId(), shot.getId(), shotContext));

        if (critique.verdict() == CritiqueVerdict.NEEDS_HUMAN_REVIEW) {
            generationThoughtService.log(tenantId, shotId, "CRITIQUE_BLOCKED",
                    "Pre-flight critique found unresolved blocking issues -- dispatch withheld pending human review");
            shot.setStatus(ShotStatus.NEEDS_REVIEW);
            shot.setUpdatedAt(OffsetDateTime.now());
            shotRepository.save(shot);
            return new ShotDispatchResponse(null, null, null, null, null, null, null, critique.sessionId(), critique.verdict(), critique.findings(),
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
                    new GenerateShotRequest(shot.getProjectId(), dispatchPlan, resolveFeatureFlags(dialogueOverride, captionsOverride), autoApprove));
            job = generationJobPersistenceService.finishSuccess(job.getId(), j -> {
                j.setExternalJobId(response.jobId());
                j.setExternalPromptId(response.promptId());
            });
            shot.setStatus(autoApprove ? ShotStatus.GENERATED : ShotStatus.PENDING_APPROVAL);
            shot.setUpdatedAt(OffsetDateTime.now());
            shotRepository.save(shot);
            generationThoughtService.log(tenantId, shotId, autoApprove ? "SHOT_GENERATED" : "SHOT_PROMPT_READY",
                    (autoApprove ? "Shot generated" : "Prompt ready for review") + " -- video-generation-service job " + response.jobId());
            return new ShotDispatchResponse(job.getId(), job.getStatus(), job.getExternalJobId(), job.getExternalPromptId(),
                    response.recommendedModel(), response.recommendationReasoning(), response.estimatedCost(),
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

    /** Null (both fields, or the whole object) lets video-generation-service apply the project's
     * own configured default for whichever flag wasn't overridden -- this never invents a value,
     * only forwards what the caller actually chose to override. */
    private FeatureFlags resolveFeatureFlags(Boolean dialogueOverride, Boolean captionsOverride) {
        if (dialogueOverride == null && captionsOverride == null) {
            return null;
        }
        return new FeatureFlags(
                dialogueOverride == null ? null : (dialogueOverride ? FlagState.ON : FlagState.OFF),
                captionsOverride == null ? null : (captionsOverride ? FlagState.ON : FlagState.OFF));
    }

    /** Rebuilds the strategy-assembled context with {@code dialogueBeats} attached -- a plain
     * field addition every {@link ShotContextAssemblyStrategy} implementation would otherwise need
     * touching individually. Each beat resolves its OWN voice reference from its own
     * {@code characterKey} (shot's primary character, or the narrator when nobody's on screen --
     * see {@link ShotDialogueBeatService#resolveCharacterKey}), not just the shot's primary-
     * character cast profile -- a shot's beats can span more than one speaker. Resolution is
     * cached per distinct characterKey since a shot's beats usually share one speaker. A beat with
     * no resolvable cast voice falls back to normal (native-audio) generation for the shot --
     * video-generation-service only turns off Seedance's audio when it actually has a voice to
     * put in its place. */
    private ShotContext withDialogueBeats(ShotContext base, UUID tenantId, Shot shot, ShotAssemblyContext assemblyContext) {
        List<ShotDialogueBeat> beats = shotDialogueBeatRepository.findByShotIdOrderByOrderIndexAsc(shot.getId());
        if (beats.isEmpty()) {
            return base;
        }
        Script script = scriptRepository.findByProjectId(shot.getProjectId()).orElse(null);
        Map<String, BeatVoice> voiceByCharacterKey = new HashMap<>();
        String languageCode = assemblyContext.projectConfig() == null ? null : assemblyContext.projectConfig().getDialogueLanguage();
        List<DialogueBeat> dialogueBeats = beats.stream()
                .map(b -> {
                    BeatVoice voice = resolveBeatVoice(tenantId, script, b.getCharacterKey(), assemblyContext, voiceByCharacterKey);
                    return new DialogueBeat(b.getStartSeconds(), b.getDurationSeconds(), b.getText(), b.getCharacterKey(),
                            voice.referenceUrl(), voice.clonedVoiceId(), voice.clonedVoiceProviderId(),
                            voice.builtinVoiceId(), shot.getEmotion(), languageCode);
                })
                .collect(Collectors.toList());
        return new ShotContext(base.shotRef(), base.narrative(), base.characters(), base.environment(), base.lighting(),
                base.camera(), base.productBrand(), base.technical(), base.continuityAnchors(), base.audioAmbience(), dialogueBeats);
    }

    /** A beat's speaking character resolves to a prepared clone, raw sample, or stock voice. A
     * prepared clone wins: it is the reusable result of a successful Prepare All Dialogues call. */
    private record BeatVoice(String referenceUrl, String clonedVoiceId, String clonedVoiceProviderId,
                             String builtinVoiceId) {
        static final BeatVoice NONE = new BeatVoice(null, null, null, null);
    }

    private BeatVoice resolveBeatVoice(UUID tenantId, Script script, String characterKey,
                                        ShotAssemblyContext assemblyContext, Map<String, BeatVoice> cache) {
        if (characterKey == null) {
            return BeatVoice.NONE;
        }
        if (cache.containsKey(characterKey)) {
            return cache.get(characterKey);
        }
        CastProfile profile = characterKey.equals(assemblyContext.shot().getPrimaryCharacterKey()) && assemblyContext.castProfile() != null
                ? assemblyContext.castProfile()
                : resolveCastProfile(tenantId, script, characterKey);
        BeatVoice voice = BeatVoice.NONE;
        if (profile != null) {
            if (hasText(profile.getClonedVoiceId()) && hasText(profile.getClonedVoiceProviderId())) {
                voice = new BeatVoice(null, profile.getClonedVoiceId(), profile.getClonedVoiceProviderId(), null);
            } else if (hasText(profile.getVoiceRefBucket()) && hasText(profile.getVoiceRefObjectKey())) {
                voice = new BeatVoice(signedUrl(profile.getVoiceRefBucket(), profile.getVoiceRefObjectKey()), null, null, null);
            } else if (hasText(profile.getBuiltinVoiceId())) {
                voice = new BeatVoice(null, null, null, profile.getBuiltinVoiceId());
            }
        }
        cache.put(characterKey, voice);
        return voice;
    }

    private CastProfile resolveCastProfile(UUID tenantId, Script script, String characterKey) {
        if (script == null) {
            return null;
        }
        return scriptCharacterRepository.findByScriptIdAndCharacterKey(script.getId(), characterKey)
                .flatMap(character -> castAssignmentRepository.findByProjectIdAndScriptCharacterId(script.getProjectId(), character.getId()))
                .flatMap(assignment -> castProfileRepository.findByIdAndTenantId(assignment.getCastProfileId(), tenantId))
                .orElse(null);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String signedUrl(String bucket, String objectKey) {
        try {
            return publicMinioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(bucket)
                    .object(objectKey)
                    .expiry(1, TimeUnit.HOURS)
                    .build());
        } catch (Exception ex) {
            return null;
        }
    }

    private ShotAssemblyContext buildAssemblyContext(UUID tenantId, Shot shot) {
        Project project = projectRepository.findByIdAndTenantId(shot.getProjectId(), tenantId)
                .orElseThrow(() -> PreProductionException.notFound("No project " + shot.getProjectId()));
        ProjectConfig projectConfig = projectConfigRepository.findByProjectId(project.getId()).orElse(null);
        ScreenplayScene scene = screenplaySceneRepository.findById(shot.getScreenplaySceneId()).orElse(null);

        List<Shot> allShots = shotRepository.findByProjectIdOrderByShotNumberAsc(shot.getProjectId());
        int index = 0;
        for (int i = 0; i < allShots.size(); i++) {
            if (allShots.get(i).getId().equals(shot.getId())) {
                index = i;
                break;
            }
        }
        EmotionalArcPosition arcPosition = EmotionalArcPositionCalculator.fromOrdinal(index, Math.max(allShots.size(), 1));
        Shot previousShot = index > 0 ? allShots.get(index - 1) : null;

        Script script = scriptRepository.findByProjectId(project.getId()).orElse(null);
        // No one on screen but there's still a line to speak -- the narrator (never in
        // primaryCharacterKey, per CharacterType's own javadoc) is who's actually talking, and
        // DialogueShotContextAssemblyStrategy requires a resolved cast for any DIALOGUE-typed shot.
        ScriptCharacter character = null;
        if (shot.getPrimaryCharacterKey() != null) {
            character = script == null ? null
                    : scriptCharacterRepository.findByScriptIdAndCharacterKey(script.getId(), shot.getPrimaryCharacterKey()).orElse(null);
        } else if (script != null && shot.getVoiceOver() != null && !shot.getVoiceOver().isBlank()) {
            character = scriptCharacterRepository.findFirstByScriptIdAndCharacterType(script.getId(), CharacterType.NARRATOR).orElse(null);
        }
        CastAssignment castAssignment = null;
        CastProfile castProfile = null;
        if (character != null) {
            castAssignment = castAssignmentRepository.findByProjectIdAndScriptCharacterId(project.getId(), character.getId()).orElse(null);
            if (castAssignment != null) {
                castProfile = castProfileRepository.findByIdAndTenantId(castAssignment.getCastProfileId(), tenantId).orElse(null);
            }
        }
        List<ContinuityLock> continuityLocks = continuityBibleService.getLocks(project.getId());
        return new ShotAssemblyContext(shot, project, projectConfig, scene, arcPosition, castAssignment, castProfile, continuityLocks, previousShot);
    }
}
