package com.dalai.llama.preprod.service;

import com.dalai.llama.preprod.domain.AspectRatio;
import com.dalai.llama.preprod.domain.CharacterType;
import com.dalai.llama.preprod.domain.ExecutionDifficulty;
import com.dalai.llama.preprod.domain.MoodProfile;
import com.dalai.llama.preprod.domain.ProjectStatus;
import com.dalai.llama.preprod.domain.ShotSize;
import com.dalai.llama.preprod.domain.ShotStatus;
import com.dalai.llama.preprod.domain.ShotType;
import com.dalai.llama.preprod.domain.TimeOfDay;
import com.dalai.llama.preprod.domain.entity.CastAssignment;
import com.dalai.llama.preprod.domain.entity.CastProfile;
import com.dalai.llama.preprod.domain.entity.Project;
import com.dalai.llama.preprod.domain.entity.Screenplay;
import com.dalai.llama.preprod.domain.entity.ScreenplayScene;
import com.dalai.llama.preprod.domain.entity.Script;
import com.dalai.llama.preprod.domain.entity.ScriptCharacter;
import com.dalai.llama.preprod.domain.entity.Shot;
import com.dalai.llama.preprod.dto.CreateShotRequest;
import com.dalai.llama.preprod.dto.ShotCastView;
import com.dalai.llama.preprod.dto.ShotView;
import com.dalai.llama.preprod.dto.UpdateShotRequest;
import com.dalai.llama.preprod.repository.CastAssignmentRepository;
import com.dalai.llama.preprod.repository.CastProfileRepository;
import com.dalai.llama.preprod.repository.ProjectRepository;
import com.dalai.llama.preprod.repository.ScreenplayRepository;
import com.dalai.llama.preprod.repository.ScreenplaySceneRepository;
import com.dalai.llama.preprod.repository.ScriptCharacterRepository;
import com.dalai.llama.preprod.repository.ScriptRepository;
import com.dalai.llama.preprod.repository.ShotRepository;
import com.dalai.llama.preprod.service.generation.CinematographyMapper;
import com.dalai.llama.preprod.service.generation.JsonExtraction;
import com.dalai.llama.preprod.service.generation.LenientStringDeserializer;
import com.dalai.llama.preprod.service.generation.ShotListGenerationResult;
import com.dalai.llama.preprod.service.generation.TolerantEnumParser;
import com.dalai.llama.preprod.service.llmgateway.LlmGatewayChatRequest;
import com.dalai.llama.preprod.service.llmgateway.LlmGatewayChatRequest.LlmGatewayMessage;
import com.dalai.llama.preprod.service.llmgateway.LlmGatewayChatResponse;
import com.dalai.llama.preprod.service.llmgateway.LlmGatewayClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.http.Method;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
public class ShotListGenerationService {

    private static final Logger log = LoggerFactory.getLogger(ShotListGenerationService.class);

    private static final String TASK_KEY = "PRE_PROD_SHOT_LIST_GENERATE";
    private static final int DEFAULT_SHOT_DURATION_SECONDS = 4;

    private final ProjectRepository projectRepository;
    private final ScriptRepository scriptRepository;
    private final ScriptCharacterRepository scriptCharacterRepository;
    private final ScreenplayRepository screenplayRepository;
    private final ScreenplaySceneRepository screenplaySceneRepository;
    private final ShotRepository shotRepository;
    private final CastAssignmentRepository castAssignmentRepository;
    private final CastProfileRepository castProfileRepository;
    private final LlmGatewayClient llmGatewayClient;
    /** A copy of the injected bean, not the bean itself -- registers LenientStringDeserializer for
     * every String field in ShotListGenerationResult, scoped to parsing LLM shot-list output only
     * (see that class's javadoc). Never touches the shared ObjectMapper used for real API request/
     * response bodies elsewhere in this service. */
    private final ObjectMapper lenientObjectMapper;
    private final ProjectService projectService;
    private final ProjectConfigService projectConfigService;
    private final ContinuityBibleService continuityBibleService;
    private final ShotPlanQualityService shotPlanQualityService;
    private final MotionGraphicPlanService motionGraphicPlanService;
    private final LightingPlanService lightingPlanService;
    private final CameraPlanService cameraPlanService;
    private final ShotFoleyCueService shotFoleyCueService;
    private final ShotImageService shotImageService;
    private final MinioClient publicMinioClient;
    private final String defaultModel;

    public ShotListGenerationService(
            ProjectRepository projectRepository,
            ScriptRepository scriptRepository,
            ScriptCharacterRepository scriptCharacterRepository,
            ScreenplayRepository screenplayRepository,
            ScreenplaySceneRepository screenplaySceneRepository,
            ShotRepository shotRepository,
            CastAssignmentRepository castAssignmentRepository,
            CastProfileRepository castProfileRepository,
            LlmGatewayClient llmGatewayClient,
            ObjectMapper objectMapper,
            ProjectService projectService,
            ProjectConfigService projectConfigService,
            ContinuityBibleService continuityBibleService,
            ShotPlanQualityService shotPlanQualityService,
            MotionGraphicPlanService motionGraphicPlanService,
            LightingPlanService lightingPlanService,
            CameraPlanService cameraPlanService,
            ShotFoleyCueService shotFoleyCueService,
            ShotImageService shotImageService,
            @Qualifier("publicMinioClient") MinioClient publicMinioClient,
            @Value("${pre-production.llm-gateway.default-text-model}") String defaultModel
    ) {
        this.projectRepository = projectRepository;
        this.scriptRepository = scriptRepository;
        this.scriptCharacterRepository = scriptCharacterRepository;
        this.screenplayRepository = screenplayRepository;
        this.screenplaySceneRepository = screenplaySceneRepository;
        this.shotRepository = shotRepository;
        this.castAssignmentRepository = castAssignmentRepository;
        this.castProfileRepository = castProfileRepository;
        this.llmGatewayClient = llmGatewayClient;
        this.lenientObjectMapper = objectMapper.copy()
                .registerModule(new SimpleModule().addDeserializer(String.class, new LenientStringDeserializer()));
        this.projectService = projectService;
        this.projectConfigService = projectConfigService;
        this.continuityBibleService = continuityBibleService;
        this.shotPlanQualityService = shotPlanQualityService;
        this.motionGraphicPlanService = motionGraphicPlanService;
        this.lightingPlanService = lightingPlanService;
        this.cameraPlanService = cameraPlanService;
        this.shotFoleyCueService = shotFoleyCueService;
        this.shotImageService = shotImageService;
        this.publicMinioClient = publicMinioClient;
        this.defaultModel = defaultModel;
    }

    @Transactional
    public List<ShotView> generate(UUID tenantId, UUID projectId) {
        ShotListJobPreparation prep = prepareJob(tenantId, projectId);
        LlmGatewayChatResponse response = llmGatewayClient.chat(
                tenantId.toString(), prep.idempotencyKey(), prep.request());
        return persistFromLlmResponse(tenantId, projectId, response);
    }

    /** The idempotency key both the sync path and the async path use for this project's shot-list
     * LLM job. Includes the screenplay + script versions the job is being built against so a
     * regeneration after a screenplay edit doesn't hit the CACHED completed row from the previous
     * screenplay version -- llm-gateway's {@code handleExisting} returns the stored resultContent
     * as-is for any COMPLETED row with the same key, and until this carried the version a creator
     * who edited their screenplay and regenerated the shot list would just get the old shots back.
     * Stable within a version so a genuine replay (crash mid-request) still deduplicates against
     * one llm_job row. Exposed for the async caller to store on
     * {@link com.dalai.llama.preprod.domain.entity.ShotListJob#getLlmJobIdempotencyKey}. */
    public static String shotListIdempotencyKey(UUID projectId, UUID screenplayId, java.time.OffsetDateTime scriptUpdatedAt) {
        long scriptStamp = scriptUpdatedAt == null ? 0L : scriptUpdatedAt.toInstant().toEpochMilli();
        return "shot-list-generate-" + projectId + "-sp" + screenplayId + "-sc" + scriptStamp;
    }

    /** Preparation carrier for a shot-list generation call: the LLM request payload plus the
     * idempotency key that has to travel with it (both sync and async callers submit through the
     * same llm-gateway {@code chat} contract). Kept together so a caller can't drift the key from
     * the payload it was computed against. */
    public record ShotListJobPreparation(LlmGatewayChatRequest request, String idempotencyKey) {}

    /** Preload versions + request in one place so the idempotency key is always in sync with the
     * inputs the request was built from. Screenplay id changes on every new screenplay version
     * (each regenerate/edit inserts a new row); script updatedAt changes on every script edit --
     * either shift produces a fresh key, so llm-gateway can't replay a cached result from before
     * the change. */
    public ShotListJobPreparation prepareJob(UUID tenantId, UUID projectId) {
        Script script = scriptRepository.findByProjectId(projectId)
                .orElseThrow(() -> PreProductionException.badRequest("Project " + projectId + " has no script yet"));
        Screenplay screenplay = screenplayRepository.findTopByProjectIdOrderByVersionDesc(projectId)
                .orElseThrow(() -> PreProductionException.badRequest("Project " + projectId + " has no screenplay yet"));
        LlmGatewayChatRequest request = buildChatRequest(tenantId, projectId);
        String key = shotListIdempotencyKey(projectId, screenplay.getId(), script.getUpdatedAt());
        return new ShotListJobPreparation(request, key);
    }

    /** Assembles the exact {@link LlmGatewayChatRequest} the sync path sends -- exposed so the
     * async submission ({@link com.dalai.llama.preprod.service.ShotListGenerationJobService})
     * can publish the same payload to Kafka without duplicating the prompt-variable wiring. */
    public LlmGatewayChatRequest buildChatRequest(UUID tenantId, UUID projectId) {
        // Load-only project fetch (validation) -- the persist path below reloads it inside its
        // own transaction anyway, so this method stays safe to call from any thread.
        projectRepository.findByIdAndTenantId(projectId, tenantId)
                .orElseThrow(() -> PreProductionException.notFound("No project " + projectId));
        Script script = scriptRepository.findByProjectId(projectId)
                .orElseThrow(() -> PreProductionException.badRequest("Project " + projectId + " has no script yet"));
        Screenplay screenplay = screenplayRepository.findTopByProjectIdOrderByVersionDesc(projectId)
                .orElseThrow(() -> PreProductionException.badRequest("Project " + projectId + " has no screenplay yet"));
        List<ScreenplayScene> scenes = screenplaySceneRepository.findByScreenplayIdOrderBySceneNumberAsc(screenplay.getId());
        if (scenes.isEmpty()) {
            throw PreProductionException.badRequest("Screenplay for project " + projectId + " has no scenes");
        }
        List<ScriptCharacter> allCharacters = scriptCharacterRepository.findByScriptId(script.getId());
        List<String> knownCharacterKeys = allCharacters.stream()
                .map(ScriptCharacter::getCharacterKey)
                .collect(Collectors.toList());
        // Full character block sent as {{characterProfiles}} in v10+ of the prompt. Old
        // {{characterKeys}} is still emitted for back-compat with older template versions but is
        // just a bare comma list -- v9 and earlier used it, v10 replaced it because the LLM
        // needed to know each character's type (antagonist vs protagonist) and description in
        // order to distribute them across scenes correctly.
        String characterProfiles = allCharacters.stream()
                .map(c -> "- key=" + c.getCharacterKey()
                        + " | name=" + (c.getCharacterName() == null ? "" : c.getCharacterName())
                        + " | type=" + (c.getCharacterType() == null ? "" : c.getCharacterType().name())
                        + (c.getCharacterRole() == null || c.getCharacterRole().isBlank() ? "" : " | role=" + c.getCharacterRole())
                        + " | description=" + (c.getDescription() == null ? "" : c.getDescription()))
                .collect(Collectors.joining("\n"));
        if (characterProfiles.isBlank()) characterProfiles = "(no characters registered on this script)";
        var projectConfig = projectConfigService.getEntityOrDefault(projectId);
        AspectRatio configuredAspectRatio = projectConfig == null ? null : projectConfig.getAspectRatio();
        boolean preferMotionGraphics = projectConfig != null && Boolean.TRUE.equals(projectConfig.getPreferMotionGraphics());

        return new LlmGatewayChatRequest(defaultModel, List.of(new LlmGatewayMessage("user", "")),
                JsonExtraction.JSON_MODE_PARAMS, TASK_KEY,
                Map.of("scriptText", script.getScriptText(),
                        "characterKeys", String.join(", ", knownCharacterKeys),
                        "characterProfiles", characterProfiles,
                        // The script itself is written in the project's dialogue language, and
                        // without being told otherwise the model mirrors that language into every
                        // descriptive field too -- producing Hindi camera notes and scene
                        // descriptions that then flow into the video prompt. The template uses
                        // this to keep descriptions English and let only spoken/on-screen text
                        // follow the project's language.
                        "dialogueLanguage", projectConfig == null || projectConfig.getDialogueLanguage() == null
                                || projectConfig.getDialogueLanguage().isBlank()
                                ? "English"
                                : projectConfig.getDialogueLanguage(),
                        "aspectRatio", configuredAspectRatio == null ? "no preference set -- choose what suits each shot" : configuredAspectRatio.toString(),
                        "motionGraphicsGuidance", preferMotionGraphics
                                ? "This project prefers MOTION_GRAPHIC for any text/data/graphic-driven beat -- classify those shots as MOTION_GRAPHIC rather than ACTION or B_ROLL."
                                : "Only use MOTION_GRAPHIC where the beat is clearly a graphic/text/data overlay, not a live-action moment.")).withProjectId(projectId);
    }

    /** Post-LLM-response half of shot list generation: parse, persist shots, advance project
     * status, kick off dependent refreshes. Called by the sync {@link #generate} path as well
     * as the async {@link com.dalai.llama.preprod.kafka.ChatJobCompletedConsumer} handler. */
    @Transactional
    public List<ShotView> persistFromLlmResponse(UUID tenantId, UUID projectId, LlmGatewayChatResponse response) {
        Project project = projectRepository.findByIdAndTenantId(projectId, tenantId)
                .orElseThrow(() -> PreProductionException.notFound("No project " + projectId));
        Script script = scriptRepository.findByProjectId(projectId)
                .orElseThrow(() -> PreProductionException.badRequest("Project " + projectId + " has no script yet"));
        Screenplay screenplay = screenplayRepository.findTopByProjectIdOrderByVersionDesc(projectId)
                .orElseThrow(() -> PreProductionException.badRequest("Project " + projectId + " has no screenplay yet"));
        List<ScreenplayScene> scenes = screenplaySceneRepository.findByScreenplayIdOrderBySceneNumberAsc(screenplay.getId());
        if (scenes.isEmpty()) {
            throw PreProductionException.badRequest("Screenplay for project " + projectId + " has no scenes");
        }
        Map<Integer, UUID> sceneIdByNumber = scenes.stream()
                .collect(Collectors.toMap(ScreenplayScene::getSceneNumber, ScreenplayScene::getId, (a, b) -> a));
        // Also index the whole scene so toShot() can propagate the needs-multi-image flag +
        // label to each shot it produces (see V66 -- the shot page reads these to reveal the
        // multi-image upload UI, and the video-gen prompt uses the label to reference the
        // bundle).
        Map<Integer, ScreenplayScene> sceneByNumber = scenes.stream()
                .collect(Collectors.toMap(ScreenplayScene::getSceneNumber, s -> s, (a, b) -> a));
        var projectConfig = projectConfigService.getEntityOrDefault(projectId);
        AspectRatio configuredAspectRatio = projectConfig == null ? null : projectConfig.getAspectRatio();

        ShotListGenerationResult parsed = parse(response);
        if (parsed.shots() == null || parsed.shots().isEmpty()) {
            throw PreProductionException.upstream("PRE_PROD_SHOT_LIST_GENERATE returned no shots");
        }

        OffsetDateTime now = OffsetDateTime.now();
        shotRepository.deleteAll(shotRepository.findByProjectIdOrderByShotNumberAsc(projectId));
        // Regenerating an already-shot-listed project hits uq_shot_ref (project_id, shot_ref)
        // without this: Hibernate's flush always runs every queued action in one fixed order --
        // insertions, then updates, then deletions -- regardless of the order they were called in
        // this method. The deletes above and the inserts below are both still just queued at this
        // point; without forcing them to hit the database now, the old rows this project already
        // had are still there when the new rows with the same shot_ref try to insert. shot_ref is
        // deliberately unique per project (see its own javadoc: the stable identity every other
        // service references a shot by), so the fix is flushing the deletes first, not loosening
        // that constraint.
        shotRepository.flush();

        AspectRatio defaultAspectRatio = configuredAspectRatio == null ? AspectRatio.RATIO_9_16 : configuredAspectRatio;
        // Order items by (scene, shot-within-scene) so the persisted shot_number reflects the
        // narrative sequence the screenplay dictates. The LLM's shotNumber is scene-SCOPED
        // (1..N per scene), which used to be persisted verbatim -- every scene's first shot got
        // shot_number=1, later ordering by shot_number was undefined for ties, and the film
        // assembled scenes in an arbitrary order. Sorting by (sceneNumber, shotNumber) and then
        // renumbering 1..N globally gives one canonical project-wide order and unblocks reorder
        // (which also expects unique, dense shot_numbers).
        List<ShotListGenerationResult.ShotItem> ordered = new ArrayList<>(parsed.shots());
        ordered.sort(java.util.Comparator
                .comparingInt((ShotListGenerationResult.ShotItem it) -> it.sceneNumber() == null ? Integer.MAX_VALUE : it.sceneNumber())
                .thenComparingInt(it -> it.shotNumber() == null ? Integer.MAX_VALUE : it.shotNumber()));
        int[] seq = {0};
        List<Shot> shots = ordered.stream()
                .map(item -> {
                    Shot shot = toShot(tenantId, projectId, project.getLockedIdeaId(), sceneIdByNumber, sceneByNumber, item, now, defaultAspectRatio);
                    shot.setShotNumber(++seq[0]);
                    return shot;
                })
                .map(shotRepository::save)
                .collect(Collectors.toList());

        continuityBibleService.refresh(tenantId, projectId);
        shotPlanQualityService.refresh(tenantId, projectId);
        projectService.advanceStatus(tenantId, projectId, ProjectStatus.SHOT_LIST_READY);

        Map<String, ShotCastView> castByCharacterKey = resolveCastByCharacterKey(tenantId, projectId, script.getId());
        return shots.stream().map(s -> toView(s, castByCharacterKey)).collect(Collectors.toList());
    }

    /** A MOTION_GRAPHIC shot has nothing for video-generation-service to dispatch to -- it needs
     * a {@link MotionGraphicPlanService} plan instead (see {@code ShotType.MOTION_GRAPHIC}'s
     * javadoc), so this plans every one up front rather than leaving the creator to notice and
     * trigger it per shot later. Called by {@code ShotListController} AFTER {@link #generate}
     * returns -- deliberately outside that method's transaction (and outside any transaction of
     * its own), because {@link MotionGraphicPlanService#generate} needs to read back the shot rows
     * this just wrote, which a still-open ambient transaction wouldn't have committed yet for a
     * REQUIRES_NEW call to see. Best-effort per shot: one LLM hiccup planning a graphic shouldn't
     * cost the creator the shot list that just generated successfully; an unplanned shot still
     * shows the "Plan this motion graphic" action in the UI as a manual fallback. */
    public void planMotionGraphicShots(UUID tenantId, List<ShotView> shots) {
        for (ShotView shot : shots) {
            if (shot.shotType() != ShotType.MOTION_GRAPHIC) {
                continue;
            }
            try {
                motionGraphicPlanService.generate(tenantId, shot.id());
            } catch (Exception ex) {
                log.warn("Could not auto-plan motion graphic for shot {}: {}", shot.id(), ex.getMessage());
                // Skip the preview image too -- with no plan to drive it, buildMotionGraphicPreviewPrompt
                // falls back to shot fields alone and produces a low-quality generic frame. A manual
                // regenerate from the UI is a better remediation than an auto-fired half-baked one.
                continue;
            }
            try {
                // Preview of the on-screen graphic itself -- MOTION_GRAPHIC shots have no
                // storyboard/lighting/camera-plan images (those are cinematography-driven and this
                // shot type has no cinematography by design); this is their equivalent visual, driven
                // by the plan we just wrote. Best-effort like every other auto-generated image.
                shotImageService.generate(tenantId, shot.id(), com.dalai.llama.preprod.domain.ShotImageKind.MOTION_GRAPHIC);
            } catch (Exception ex) {
                log.warn("Could not auto-generate motion-graphic preview image for shot {}: {}", shot.id(), ex.getMessage());
            }
        }
    }

    /** Every camera-planned shot (everything except MOTION_GRAPHIC, which has no cinematography
     * to plan around -- see ShotType's javadoc) gets a lighting + camera plan up front, same
     * "don't make the creator notice and manually trigger it" reasoning as {@link
     * #planMotionGraphicShots}. Called by {@code ShotListController} AFTER {@link #generate}
     * returns, for the same reason: LightingPlanService/CameraPlanService read the shot rows this
     * just wrote, which a still-open transaction wouldn't have committed yet. Best-effort per shot
     * and per plan kind -- one LLM hiccup (or a failed critique retry) planning one shot's lighting
     * should never cost the creator the shot list, or the other 3 plans/images this shot still
     * gets. The manual "Plan"/"Replan" actions in LightingCameraPlanPanel stay as a fallback for
     * whatever this pass didn't reach, and as how a creator revises one after editing the shot. */
    public void planLightingAndCameraForShots(UUID tenantId, List<ShotView> shots) {
        for (ShotView shot : shots) {
            if (shot.shotType() == ShotType.MOTION_GRAPHIC) {
                continue;
            }
            try {
                lightingPlanService.generate(tenantId, shot.id());
            } catch (Exception ex) {
                log.warn("Could not auto-plan lighting for shot {}: {}", shot.id(), ex.getMessage());
            }
            try {
                cameraPlanService.generate(tenantId, shot.id());
            } catch (Exception ex) {
                log.warn("Could not auto-plan camera for shot {}: {}", shot.id(), ex.getMessage());
            }
            // Derived here, once, rather than on every prepare in video-generation-service. Same
            // best-effort contract as the other plans: no cue sheet is a shot that generates
            // without one, not a failed shot list.
            try {
                shotFoleyCueService.generate(tenantId, shot.id());
            } catch (Exception ex) {
                log.warn("Could not auto-derive foley cues for shot {}: {}", shot.id(), ex.getMessage());
            }
        }
    }

    @Transactional(readOnly = true)
    public List<ShotView> list(UUID tenantId, UUID projectId) {
        Map<String, ShotCastView> castByCharacterKey = castByCharacterKeyForProject(tenantId, projectId);
        return shotRepository.findByProjectIdOrderByShotNumberAsc(projectId).stream()
                .map(s -> toView(s, castByCharacterKey))
                .collect(Collectors.toList());
    }

    /** Manually inserts one shot into an existing screenplay scene -- see {@link
     * CreateShotRequest}'s class comment for what this deliberately does and doesn't populate.
     * shotNumber continues the scene's own sequence (existing shots in that scene, not the whole
     * project), matching shot_ref's "shot-{sceneNumber}-{shotNumber}" convention {@link #toShot}
     * already establishes for AI-generated shots. */
    public ShotView createShot(UUID tenantId, UUID projectId, CreateShotRequest request) {
        Project project = projectRepository.findByIdAndTenantId(projectId, tenantId)
                .orElseThrow(() -> PreProductionException.notFound("No project " + projectId));
        Screenplay screenplay = screenplayRepository.findTopByProjectIdOrderByVersionDesc(projectId)
                .orElseThrow(() -> PreProductionException.badRequest("Project " + projectId + " has no screenplay yet"));
        ScreenplayScene scene = screenplaySceneRepository.findByIdAndTenantId(request.screenplaySceneId(), tenantId)
                .filter(s -> s.getScreenplayId().equals(screenplay.getId()))
                .orElseThrow(() -> PreProductionException.badRequest(
                        "screenplaySceneId=" + request.screenplaySceneId() + " does not belong to project " + projectId + "'s current screenplay"));

        // shot_number is PROJECT-wide (film assembly, reorder, storyboard all order by it), so
        // a hand-created shot has to take max-across-the-project + 1, not max-within-the-scene +
        // 1. The scene-scoped version silently collided with existing shots in later scenes and
        // broke the global order. Creator can drag the appended shot into place via the reorder
        // endpoint afterwards.
        int nextShotNumber = shotRepository.findByProjectIdOrderByShotNumberAsc(projectId).stream()
                .mapToInt(Shot::getShotNumber)
                .max()
                .orElse(0) + 1;
        // shot_ref keeps its scene-scoped display form ("shot-{scene:02d}-{seq:03d}") so a
        // creator glancing at refs still sees which scene a shot belongs to; the scene-local
        // sequence for the ref is just "count of shots already in this scene + 1" -- shot_number
        // is global now, so it can't be reused as the intra-scene index.
        int refSequenceWithinScene = shotRepository.findByScreenplaySceneIdOrderByShotNumberAsc(scene.getId()).size() + 1;
        var projectConfig = projectConfigService.getEntityOrDefault(projectId);
        AspectRatio aspectRatio = projectConfig == null || projectConfig.getAspectRatio() == null
                ? AspectRatio.RATIO_9_16 : projectConfig.getAspectRatio();
        OffsetDateTime now = OffsetDateTime.now();
        Shot shot = Shot.builder()
                .tenantId(tenantId)
                .projectId(projectId)
                .lockedIdeaId(project.getLockedIdeaId())
                .screenplaySceneId(scene.getId())
                // Manually-created shots inherit the multi-image flag from their parent scene
                // too, same as the AI-generated toShot() path -- otherwise a hand-added shot in a
                // flagged scene wouldn't show the upload UI.
                .needsMultiImage(Boolean.TRUE.equals(scene.getNeedsMultiImage()))
                .multiImageLabel(scene.getMultiImageLabel())
                .sceneType(scene.getSceneType())
                .shotRef("shot-%02d-%03d".formatted(scene.getSceneNumber(), refSequenceWithinScene))
                .shotNumber(nextShotNumber)
                .shotType(request.shotType() == null ? ShotType.ACTION : request.shotType())
                .scriptLine(request.scriptLine())
                .cameraShotSize(ShotSize.MS)
                .timeOfDay(TimeOfDay.MIDDAY)
                .lightingMood(MoodProfile.SOFT)
                .durationSeconds(request.durationSeconds() == null ? DEFAULT_SHOT_DURATION_SECONDS : request.durationSeconds())
                .aspectRatio(aspectRatio)
                .status(ShotStatus.READY)
                .executionDifficulty(ExecutionDifficulty.MEDIUM)
                .createdAt(now)
                .updatedAt(now)
                .build();
        Shot saved = shotRepository.save(shot);
        continuityBibleService.refresh(tenantId, projectId);
        shotPlanQualityService.refresh(tenantId, projectId);
        return toView(saved, castByCharacterKeyForProject(tenantId, projectId));
    }

    /** Hand-edit of a shot's plan -- see {@link UpdateShotRequest}'s class comment. PATCH semantics
     * throughout: a null field on the request leaves the shot column untouched; a non-null field
     * (including an empty string, for clearing free-text notes) is applied. Deep cinematography
     * fields ({@code cine_*}) and lighting/camera gear stay in their own edit paths (LightingPlan/
     * CameraPlan editors) since that's where the DP-critic material already lives. */
    public ShotView updateShot(UUID tenantId, UUID shotId, UpdateShotRequest request) {
        Shot shot = shotRepository.findByIdAndTenantId(shotId, tenantId)
                .orElseThrow(() -> PreProductionException.notFound("No shot " + shotId));
        if (request.scriptLine() != null) shot.setScriptLine(request.scriptLine());
        if (request.durationSeconds() != null) shot.setDurationSeconds(request.durationSeconds());
        if (request.action() != null) shot.setAction(request.action());
        if (request.voiceOver() != null) shot.setVoiceOver(request.voiceOver());
        if (request.emotion() != null) shot.setEmotion(request.emotion());
        if (request.textOverlay() != null) shot.setTextOverlay(request.textOverlay());
        if (request.soundDesign() != null) shot.setSoundDesign(request.soundDesign());
        if (request.editingNotes() != null) shot.setEditingNotes(request.editingNotes());
        if (request.location() != null) shot.setLocation(request.location());
        if (request.timeOfDay() != null) shot.setTimeOfDay(request.timeOfDay());
        if (request.lightingMood() != null) shot.setLightingMood(request.lightingMood());
        if (request.cameraShotSize() != null) shot.setCameraShotSize(request.cameraShotSize());
        if (request.cameraAngle() != null) shot.setCameraAngle(request.cameraAngle());
        if (request.cameraMovement() != null) shot.setCameraMovement(request.cameraMovement());
        if (request.cameraNote() != null) shot.setCameraNote(request.cameraNote());
        shot.setUpdatedAt(OffsetDateTime.now());
        Shot saved = shotRepository.save(shot);
        return toView(saved, castByCharacterKeyForProject(tenantId, shot.getProjectId()));
    }

    private Map<String, ShotCastView> castByCharacterKeyForProject(UUID tenantId, UUID projectId) {
        return scriptRepository.findByProjectId(projectId)
                .map(script -> resolveCastByCharacterKey(tenantId, projectId, script.getId()))
                .orElseGet(Map::of);
    }

    /** Batch-resolves every character's cast assignment once per call instead of per shot -- a
     * project's shot list can be dozens of rows, all sharing the same handful of characters. Keyed
     * by characterKey (what {@code Shot.primaryCharacterKey} actually stores) so {@link #toView}
     * is a plain map lookup. */
    private Map<String, ShotCastView> resolveCastByCharacterKey(UUID tenantId, UUID projectId, UUID scriptId) {
        List<ScriptCharacter> characters = scriptCharacterRepository.findByScriptId(scriptId);
        if (characters.isEmpty()) {
            return Map.of();
        }
        List<CastAssignment> assignments = castAssignmentRepository.findByProjectId(projectId);
        Map<UUID, CastAssignment> assignmentByCharacterId = assignments.stream()
                .collect(Collectors.toMap(CastAssignment::getScriptCharacterId, a -> a, (a, b) -> a));
        List<UUID> profileIds = assignments.stream().map(CastAssignment::getCastProfileId).distinct().collect(Collectors.toList());
        Map<UUID, CastProfile> profileById = profileIds.isEmpty() ? Map.of() : castProfileRepository.findAllById(profileIds).stream()
                .collect(Collectors.toMap(CastProfile::getId, p -> p));

        Map<String, ShotCastView> result = new HashMap<>();
        for (ScriptCharacter character : characters) {
            CastAssignment assignment = assignmentByCharacterId.get(character.getId());
            CastProfile profile = assignment == null ? null : profileById.get(assignment.getCastProfileId());
            result.put(character.getCharacterKey(), new ShotCastView(
                    character.getCharacterKey(),
                    character.getCharacterName(),
                    character.getCharacterType(),
                    profile == null ? null : profile.getId(),
                    profile == null ? null : profile.getDisplayName(),
                    profile == null ? null : signedFaceUrl(profile),
                    profile != null && (profile.getVoiceRefBucket() != null || profile.getBuiltinVoiceId() != null)
            ));
        }
        return result;
    }

    private String signedFaceUrl(CastProfile profile) {
        try {
            return publicMinioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(profile.getFaceRefBucket())
                    .object(profile.getFaceRefObjectKey())
                    .expiry(1, TimeUnit.HOURS)
                    .build());
        } catch (Exception ex) {
            return null;
        }
    }

    private Shot toShot(UUID tenantId, UUID projectId, UUID lockedIdeaId,
                         Map<Integer, UUID> sceneIdByNumber, Map<Integer, ScreenplayScene> sceneByNumber,
                         ShotListGenerationResult.ShotItem item, OffsetDateTime now, AspectRatio defaultAspectRatio) {
        UUID sceneId = sceneIdByNumber.get(item.sceneNumber());
        if (sceneId == null) {
            throw PreProductionException.upstream(
                    "PRE_PROD_SHOT_LIST_GENERATE referenced unknown sceneNumber=" + item.sceneNumber());
        }
        ScreenplayScene scene = sceneByNumber.get(item.sceneNumber());
        int shotNumber = item.shotNumber() == null ? 0 : item.shotNumber();
        Shot shot = Shot.builder()
                .tenantId(tenantId)
                .projectId(projectId)
                .lockedIdeaId(lockedIdeaId)
                .screenplaySceneId(sceneId)
                .needsMultiImage(scene != null && Boolean.TRUE.equals(scene.getNeedsMultiImage()))
                .multiImageLabel(scene == null ? null : scene.getMultiImageLabel())
                .sceneType(scene == null ? null : scene.getSceneType())
                .shotRef("shot-%02d-%03d".formatted(item.sceneNumber(), shotNumber))
                .shotNumber(shotNumber)
                .shotType(TolerantEnumParser.parse(ShotType.class, item.shotType(), ShotType.ACTION))
                .scriptLine(item.scriptLine())
                .primaryCharacterKey(item.primaryCharacterKey())
                .cameraShotSize(TolerantEnumParser.parse(ShotSize.class, item.cameraShotSize(), ShotSize.MS))
                .cameraNote(item.cameraNote())
                .location(item.location())
                .timeOfDay(TolerantEnumParser.parse(TimeOfDay.class, item.timeOfDay(), TimeOfDay.MIDDAY))
                .lightingMood(TolerantEnumParser.parse(MoodProfile.class, item.lightingMood(), MoodProfile.SOFT))
                .durationSeconds(item.durationSeconds() == null ? DEFAULT_SHOT_DURATION_SECONDS : item.durationSeconds())
                .aspectRatio(TolerantEnumParser.parse(AspectRatio.class, item.aspectRatio(), defaultAspectRatio))
                .status(ShotStatus.READY)
                .cameraAngle(item.cameraAngle())
                .cameraMovement(item.cameraMovement())
                .lensSuggestion(item.lensSuggestion())
                .fps(item.fps())
                .composition(item.composition())
                .expression(item.expression())
                .emotion(item.emotion())
                .bodyLanguage(item.bodyLanguage())
                .action(item.action())
                .voiceOver(item.voiceOver())
                .textOverlay(item.textOverlay())
                .soundDesign(item.soundDesign())
                .editingNotes(item.editingNotes())
                .retentionGoal(item.retentionGoal())
                .creatorDirection(item.creatorDirection())
                .subtitlePosition(item.subtitlePosition())
                .mobileFocusArea(item.mobileFocusArea())
                .safeZoneNotes(item.safeZoneNotes())
                .executionDifficulty(TolerantEnumParser.parse(ExecutionDifficulty.class, item.executionDifficulty(), ExecutionDifficulty.MEDIUM))
                .cinematicExecution(item.cinematicExecution())
                .rookieFriendlyGuide(item.rookieFriendlyGuide())
                .sketchPrompt(item.sketchPrompt())
                .coverageType(item.coverageType())
                .screenDirection(item.screenDirection())
                .peopleInFrame(parsePeopleInFrame(item.peopleInFrame()))
                .culturalReferences(item.culturalReferences())
                .productShotType(item.productShotType())
                .shootDay(item.shootDay())
                .shootBlock(item.shootBlock())
                .directorNote(item.directorNote())
                .createdAt(now)
                .updatedAt(now)
                .build();
        CinematographyMapper.applyTo(shot, item.cinematography());
        return shot;
    }

    private ShotListGenerationResult parse(LlmGatewayChatResponse response) {
        if (response == null || response.response() == null || response.response().isBlank()) {
            throw PreProductionException.upstream("llm-gateway returned no content for PRE_PROD_SHOT_LIST_GENERATE");
        }
        try {
            return lenientObjectMapper.readValue(JsonExtraction.stripCodeFence(response.response()), ShotListGenerationResult.class);
        } catch (Exception ex) {
            throw PreProductionException.upstream("Could not parse PRE_PROD_SHOT_LIST_GENERATE response as JSON: " + ex.getMessage());
        }
    }

    private ShotView toView(Shot shot, Map<String, ShotCastView> castByCharacterKey) {
        ShotCastView primaryCast = shot.getPrimaryCharacterKey() != null
                ? castByCharacterKey.get(shot.getPrimaryCharacterKey())
                : null;
        // A PRODUCT primary character is on screen but mute -- any narration on this shot (e.g. a
        // PRODUCT_HERO shot's voiceOver) is the narrator talking, same as a shot with no primary
        // character at all. Without this, a product shot's cast/hasVoiceSample would reflect the
        // product's own (always-absent) voice, permanently disabling dialogue-beat dubbing for it.
        ShotCastView cast = primaryCast != null && primaryCast.characterType() != CharacterType.PRODUCT
                ? primaryCast
                : narratorCast(shot, castByCharacterKey);
        return new ShotView(
                shot.getId(), shot.getLockedIdeaId(), shot.getShotRef(), shot.getShotNumber(), shot.getScreenplaySceneId(),
                shot.getShotType(), shot.getScriptLine(), shot.getPrimaryCharacterKey(),
                shot.getCameraShotSize(), shot.getCameraNote(), shot.getLocation(), shot.getTimeOfDay(),
                shot.getLightingMood(), shot.getDurationSeconds(), shot.getAspectRatio(), shot.getStatus(),
                shot.getCameraAngle(), shot.getCameraMovement(), shot.getLensSuggestion(), shot.getFps(),
                shot.getComposition(), shot.getExpression(), shot.getEmotion(), shot.getBodyLanguage(),
                shot.getAction(), shot.getVoiceOver(), shot.getTextOverlay(), shot.getSoundDesign(),
                shot.getEditingNotes(), shot.getRetentionGoal(), shot.getCreatorDirection(),
                shot.getSubtitlePosition(), shot.getMobileFocusArea(), shot.getSafeZoneNotes(),
                shot.getExecutionDifficulty(), shot.getCinematicExecution(), shot.getRookieFriendlyGuide(),
                shot.getSketchPrompt(), shot.getCoverageType(), shot.getScreenDirection(), shot.getPeopleInFrame(),
                shot.getCulturalReferences(), shot.getProductShotType(), shot.getShootDay(), shot.getShootBlock(),
                shot.getDirectorNote(), CinematographyMapper.toView(shot), cast,
                Boolean.TRUE.equals(shot.getNeedsMultiImage()), shot.getMultiImageLabel());
    }

    /** No one on screen but there's still a line to speak (voiceOver, no primaryCharacterKey) --
     * the narrator, never in a scene's on-screen cast list, is who's actually talking. Falls back
     * only when there's a line to attribute; a shot with neither stays castless. */
    private ShotCastView narratorCast(Shot shot, Map<String, ShotCastView> castByCharacterKey) {
        if (shot.getVoiceOver() == null || shot.getVoiceOver().isBlank()) {
            return null;
        }
        return castByCharacterKey.values().stream()
                .filter(c -> c.characterType() == CharacterType.NARRATOR)
                .findFirst()
                .orElse(null);
    }

    /**
     * Coerce the LLM's peopleInFrame reply into the Integer column {@code shots.people_in_frame}.
     *
     * <p>Gemini is happy to emit either a plain integer ({@code 3}, {@code 12}) or a qualitative
     * word ({@code "many"}, {@code "crowd"}, {@code "3-5"}, {@code "several"}). Strict Jackson
     * binding to Integer used to fail the ENTIRE shot list persistence for one word out of
     * hundreds of fields -- Pragya's "A Healthier Mumbai Day" lost the whole 100 KB response
     * to a single {@code "many"}. We now accept both shapes: plain integer parses as such, a
     * string starting with digits parses those digits (so {@code "3-5"} → 3), anything else
     * (qualitative words, empty, null) becomes null. Null is a valid value on this column.
     */
    static Integer parsePeopleInFrame(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String trimmed = raw.trim();
        try {
            return Integer.parseInt(trimmed);
        } catch (NumberFormatException ignored) {
            // Fall through to leading-digits path -- covers "3-5", "10+", "12 people".
        }
        int end = 0;
        while (end < trimmed.length() && Character.isDigit(trimmed.charAt(end))) {
            end++;
        }
        if (end == 0) return null;
        try {
            return Integer.parseInt(trimmed.substring(0, end));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
