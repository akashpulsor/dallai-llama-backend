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
        this.shotImageService = shotImageService;
        this.publicMinioClient = publicMinioClient;
        this.defaultModel = defaultModel;
    }

    @Transactional
    public List<ShotView> generate(UUID tenantId, UUID projectId) {
        LlmGatewayChatResponse response = llmGatewayClient.chat(
                tenantId.toString(),
                shotListIdempotencyKey(projectId),
                buildChatRequest(tenantId, projectId));
        return persistFromLlmResponse(tenantId, projectId, response);
    }

    /** The idempotency key both the sync path and the async path use for this project's
     * shot-list LLM job. Stable per-project so a replay hits the same {@code llm_job} row rather
     * than costing a second Gemini call. Exposed for the async caller to store on
     * {@link com.dalai.llama.preprod.domain.entity.ShotListJob#getLlmJobIdempotencyKey}. */
    public static String shotListIdempotencyKey(UUID projectId) {
        return "shot-list-generate-" + projectId;
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
        List<String> knownCharacterKeys = scriptCharacterRepository.findByScriptId(script.getId()).stream()
                .map(ScriptCharacter::getCharacterKey)
                .collect(Collectors.toList());
        var projectConfig = projectConfigService.getEntityOrDefault(projectId);
        AspectRatio configuredAspectRatio = projectConfig == null ? null : projectConfig.getAspectRatio();
        boolean preferMotionGraphics = projectConfig != null && Boolean.TRUE.equals(projectConfig.getPreferMotionGraphics());

        return new LlmGatewayChatRequest(defaultModel, List.of(new LlmGatewayMessage("user", "")),
                JsonExtraction.JSON_MODE_PARAMS, TASK_KEY,
                Map.of("scriptText", script.getScriptText(), "characterKeys", String.join(", ", knownCharacterKeys),
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
        List<Shot> shots = parsed.shots().stream()
                .map(item -> toShot(tenantId, projectId, project.getLockedIdeaId(), sceneIdByNumber, item, now, defaultAspectRatio))
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

        int nextShotNumber = shotRepository.findByScreenplaySceneIdOrderByShotNumberAsc(scene.getId()).stream()
                .mapToInt(Shot::getShotNumber)
                .max()
                .orElse(0) + 1;
        var projectConfig = projectConfigService.getEntityOrDefault(projectId);
        AspectRatio aspectRatio = projectConfig == null || projectConfig.getAspectRatio() == null
                ? AspectRatio.RATIO_9_16 : projectConfig.getAspectRatio();
        OffsetDateTime now = OffsetDateTime.now();
        Shot shot = Shot.builder()
                .tenantId(tenantId)
                .projectId(projectId)
                .lockedIdeaId(project.getLockedIdeaId())
                .screenplaySceneId(scene.getId())
                .shotRef("shot-%02d-%03d".formatted(scene.getSceneNumber(), nextShotNumber))
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

    /** Hand-edit of a shot's script line and/or length -- see {@link UpdateShotRequest}'s class
     * comment. Every other field (camera plan, lighting, etc.) is untouched. */
    public ShotView updateShot(UUID tenantId, UUID shotId, UpdateShotRequest request) {
        Shot shot = shotRepository.findByIdAndTenantId(shotId, tenantId)
                .orElseThrow(() -> PreProductionException.notFound("No shot " + shotId));
        if (request.scriptLine() != null) {
            shot.setScriptLine(request.scriptLine());
        }
        if (request.durationSeconds() != null) {
            shot.setDurationSeconds(request.durationSeconds());
        }
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

    private Shot toShot(UUID tenantId, UUID projectId, UUID lockedIdeaId, Map<Integer, UUID> sceneIdByNumber,
                         ShotListGenerationResult.ShotItem item, OffsetDateTime now, AspectRatio defaultAspectRatio) {
        UUID sceneId = sceneIdByNumber.get(item.sceneNumber());
        if (sceneId == null) {
            throw PreProductionException.upstream(
                    "PRE_PROD_SHOT_LIST_GENERATE referenced unknown sceneNumber=" + item.sceneNumber());
        }
        int shotNumber = item.shotNumber() == null ? 0 : item.shotNumber();
        Shot shot = Shot.builder()
                .tenantId(tenantId)
                .projectId(projectId)
                .lockedIdeaId(lockedIdeaId)
                .screenplaySceneId(sceneId)
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
                .peopleInFrame(item.peopleInFrame())
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
        ShotCastView cast = shot.getPrimaryCharacterKey() != null
                ? castByCharacterKey.get(shot.getPrimaryCharacterKey())
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
                shot.getDirectorNote(), CinematographyMapper.toView(shot), cast);
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
}
