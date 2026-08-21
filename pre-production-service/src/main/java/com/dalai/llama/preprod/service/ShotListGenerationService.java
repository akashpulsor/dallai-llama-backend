package com.dalai.llama.preprod.service;

import com.dalai.llama.preprod.domain.AspectRatio;
import com.dalai.llama.preprod.domain.ExecutionDifficulty;
import com.dalai.llama.preprod.domain.MoodProfile;
import com.dalai.llama.preprod.domain.ProjectStatus;
import com.dalai.llama.preprod.domain.ShotSize;
import com.dalai.llama.preprod.domain.ShotStatus;
import com.dalai.llama.preprod.domain.ShotType;
import com.dalai.llama.preprod.domain.TimeOfDay;
import com.dalai.llama.preprod.domain.entity.Screenplay;
import com.dalai.llama.preprod.domain.entity.ScreenplayScene;
import com.dalai.llama.preprod.domain.entity.Script;
import com.dalai.llama.preprod.domain.entity.ScriptCharacter;
import com.dalai.llama.preprod.domain.entity.Shot;
import com.dalai.llama.preprod.dto.ShotView;
import com.dalai.llama.preprod.repository.ProjectRepository;
import com.dalai.llama.preprod.repository.ScreenplayRepository;
import com.dalai.llama.preprod.repository.ScreenplaySceneRepository;
import com.dalai.llama.preprod.repository.ScriptCharacterRepository;
import com.dalai.llama.preprod.repository.ScriptRepository;
import com.dalai.llama.preprod.repository.ShotRepository;
import com.dalai.llama.preprod.service.generation.CinematographyMapper;
import com.dalai.llama.preprod.service.generation.JsonExtraction;
import com.dalai.llama.preprod.service.generation.ShotListGenerationResult;
import com.dalai.llama.preprod.service.generation.TolerantEnumParser;
import com.dalai.llama.preprod.service.llmgateway.LlmGatewayChatRequest;
import com.dalai.llama.preprod.service.llmgateway.LlmGatewayChatRequest.LlmGatewayMessage;
import com.dalai.llama.preprod.service.llmgateway.LlmGatewayChatResponse;
import com.dalai.llama.preprod.service.llmgateway.LlmGatewayClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ShotListGenerationService {

    private static final String TASK_KEY = "PRE_PROD_SHOT_LIST_GENERATE";
    private static final int DEFAULT_SHOT_DURATION_SECONDS = 4;

    private final ProjectRepository projectRepository;
    private final ScriptRepository scriptRepository;
    private final ScriptCharacterRepository scriptCharacterRepository;
    private final ScreenplayRepository screenplayRepository;
    private final ScreenplaySceneRepository screenplaySceneRepository;
    private final ShotRepository shotRepository;
    private final LlmGatewayClient llmGatewayClient;
    private final ObjectMapper objectMapper;
    private final ProjectService projectService;
    private final String defaultModel;

    public ShotListGenerationService(
            ProjectRepository projectRepository,
            ScriptRepository scriptRepository,
            ScriptCharacterRepository scriptCharacterRepository,
            ScreenplayRepository screenplayRepository,
            ScreenplaySceneRepository screenplaySceneRepository,
            ShotRepository shotRepository,
            LlmGatewayClient llmGatewayClient,
            ObjectMapper objectMapper,
            ProjectService projectService,
            @Value("${pre-production.llm-gateway.default-text-model}") String defaultModel
    ) {
        this.projectRepository = projectRepository;
        this.scriptRepository = scriptRepository;
        this.scriptCharacterRepository = scriptCharacterRepository;
        this.screenplayRepository = screenplayRepository;
        this.screenplaySceneRepository = screenplaySceneRepository;
        this.shotRepository = shotRepository;
        this.llmGatewayClient = llmGatewayClient;
        this.objectMapper = objectMapper;
        this.projectService = projectService;
        this.defaultModel = defaultModel;
    }

    @Transactional
    public List<ShotView> generate(UUID tenantId, UUID projectId) {
        if (projectRepository.findByIdAndTenantId(projectId, tenantId).isEmpty()) {
            throw PreProductionException.notFound("No project " + projectId);
        }
        Script script = scriptRepository.findByProjectId(projectId)
                .orElseThrow(() -> PreProductionException.badRequest("Project " + projectId + " has no script yet"));
        Screenplay screenplay = screenplayRepository.findByProjectId(projectId)
                .orElseThrow(() -> PreProductionException.badRequest("Project " + projectId + " has no screenplay yet"));
        List<ScreenplayScene> scenes = screenplaySceneRepository.findByScreenplayIdOrderBySceneNumberAsc(screenplay.getId());
        if (scenes.isEmpty()) {
            throw PreProductionException.badRequest("Screenplay for project " + projectId + " has no scenes");
        }
        Map<Integer, UUID> sceneIdByNumber = scenes.stream()
                .collect(Collectors.toMap(ScreenplayScene::getSceneNumber, ScreenplayScene::getId, (a, b) -> a));
        List<String> knownCharacterKeys = scriptCharacterRepository.findByScriptId(script.getId()).stream()
                .map(ScriptCharacter::getCharacterKey)
                .collect(Collectors.toList());

        LlmGatewayChatResponse response = llmGatewayClient.chat(
                tenantId.toString(),
                "shot-list-generate-" + projectId,
                new LlmGatewayChatRequest(defaultModel, List.of(new LlmGatewayMessage("user", "")),
                        JsonExtraction.JSON_MODE_PARAMS, TASK_KEY,
                        Map.of("scriptText", script.getScriptText(), "characterKeys", String.join(", ", knownCharacterKeys))));

        ShotListGenerationResult parsed = parse(response);
        if (parsed.shots() == null || parsed.shots().isEmpty()) {
            throw PreProductionException.upstream("PRE_PROD_SHOT_LIST_GENERATE returned no shots");
        }

        OffsetDateTime now = OffsetDateTime.now();
        shotRepository.deleteAll(shotRepository.findByProjectIdOrderByShotNumberAsc(projectId));

        List<Shot> shots = parsed.shots().stream()
                .map(item -> toShot(tenantId, projectId, sceneIdByNumber, item, now))
                .map(shotRepository::save)
                .collect(Collectors.toList());

        projectService.advanceStatus(tenantId, projectId, ProjectStatus.SHOT_LIST_READY);

        return shots.stream().map(this::toView).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<ShotView> list(UUID tenantId, UUID projectId) {
        return shotRepository.findByProjectIdOrderByShotNumberAsc(projectId).stream()
                .map(this::toView)
                .collect(Collectors.toList());
    }

    private Shot toShot(UUID tenantId, UUID projectId, Map<Integer, UUID> sceneIdByNumber,
                         ShotListGenerationResult.ShotItem item, OffsetDateTime now) {
        UUID sceneId = sceneIdByNumber.get(item.sceneNumber());
        if (sceneId == null) {
            throw PreProductionException.upstream(
                    "PRE_PROD_SHOT_LIST_GENERATE referenced unknown sceneNumber=" + item.sceneNumber());
        }
        int shotNumber = item.shotNumber() == null ? 0 : item.shotNumber();
        Shot shot = Shot.builder()
                .tenantId(tenantId)
                .projectId(projectId)
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
                .aspectRatio(TolerantEnumParser.parse(AspectRatio.class, item.aspectRatio(), AspectRatio.RATIO_9_16))
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
            return objectMapper.readValue(JsonExtraction.stripCodeFence(response.response()), ShotListGenerationResult.class);
        } catch (Exception ex) {
            throw PreProductionException.upstream("Could not parse PRE_PROD_SHOT_LIST_GENERATE response as JSON: " + ex.getMessage());
        }
    }

    private ShotView toView(Shot shot) {
        return new ShotView(
                shot.getId(), shot.getShotRef(), shot.getShotNumber(), shot.getScreenplaySceneId(),
                shot.getShotType(), shot.getScriptLine(), shot.getPrimaryCharacterKey(),
                shot.getCameraShotSize(), shot.getCameraNote(), shot.getLocation(), shot.getTimeOfDay(),
                shot.getLightingMood(), shot.getDurationSeconds(), shot.getAspectRatio(), shot.getStatus(),
                shot.getCameraAngle(), shot.getCameraMovement(), shot.getLensSuggestion(), shot.getFps(),
                shot.getComposition(), shot.getExpression(), shot.getEmotion(), shot.getBodyLanguage(),
                shot.getAction(), shot.getVoiceOver(), shot.getTextOverlay(), shot.getSoundDesign(),
                shot.getEditingNotes(), shot.getRetentionGoal(), shot.getCreatorDirection(),
                shot.getSubtitlePosition(), shot.getMobileFocusArea(), shot.getSafeZoneNotes(),
                shot.getExecutionDifficulty(), shot.getCinematicExecution(), shot.getRookieFriendlyGuide(),
                shot.getSketchPrompt(), CinematographyMapper.toView(shot));
    }
}
