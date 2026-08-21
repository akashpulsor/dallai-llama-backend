package com.dalai.llama.preprod.service;

import com.dalai.llama.preprod.domain.DraftStatus;
import com.dalai.llama.preprod.domain.ProjectStatus;
import com.dalai.llama.preprod.domain.TimeOfDay;
import com.dalai.llama.preprod.domain.entity.Screenplay;
import com.dalai.llama.preprod.domain.entity.ScreenplayScene;
import com.dalai.llama.preprod.domain.entity.Script;
import com.dalai.llama.preprod.dto.ScreenplaySceneView;
import com.dalai.llama.preprod.dto.ScreenplayView;
import com.dalai.llama.preprod.repository.ProjectRepository;
import com.dalai.llama.preprod.repository.ScreenplayRepository;
import com.dalai.llama.preprod.repository.ScreenplaySceneRepository;
import com.dalai.llama.preprod.repository.ScriptRepository;
import com.dalai.llama.preprod.service.generation.JsonExtraction;
import com.dalai.llama.preprod.service.generation.ScreenplayGenerationResult;
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
public class ScreenplayGenerationService {

    private static final String TASK_KEY = "PRE_PROD_SCREENPLAY_GENERATE";

    private final ProjectRepository projectRepository;
    private final ScriptRepository scriptRepository;
    private final ScreenplayRepository screenplayRepository;
    private final ScreenplaySceneRepository screenplaySceneRepository;
    private final LlmGatewayClient llmGatewayClient;
    private final ObjectMapper objectMapper;
    private final ProjectService projectService;
    private final String defaultModel;

    public ScreenplayGenerationService(
            ProjectRepository projectRepository,
            ScriptRepository scriptRepository,
            ScreenplayRepository screenplayRepository,
            ScreenplaySceneRepository screenplaySceneRepository,
            LlmGatewayClient llmGatewayClient,
            ObjectMapper objectMapper,
            ProjectService projectService,
            @Value("${pre-production.llm-gateway.default-text-model}") String defaultModel
    ) {
        this.projectRepository = projectRepository;
        this.scriptRepository = scriptRepository;
        this.screenplayRepository = screenplayRepository;
        this.screenplaySceneRepository = screenplaySceneRepository;
        this.llmGatewayClient = llmGatewayClient;
        this.objectMapper = objectMapper;
        this.projectService = projectService;
        this.defaultModel = defaultModel;
    }

    @Transactional
    public ScreenplayView generate(UUID tenantId, UUID projectId) {
        if (projectRepository.findByIdAndTenantId(projectId, tenantId).isEmpty()) {
            throw PreProductionException.notFound("No project " + projectId);
        }
        Script script = scriptRepository.findByProjectId(projectId)
                .orElseThrow(() -> PreProductionException.badRequest("Project " + projectId + " has no script yet"));

        LlmGatewayChatResponse response = llmGatewayClient.chat(
                tenantId.toString(),
                "screenplay-generate-" + projectId,
                new LlmGatewayChatRequest(defaultModel, List.of(new LlmGatewayMessage("user", "")),
                        JsonExtraction.JSON_MODE_PARAMS, TASK_KEY,
                        Map.of("scriptText", script.getScriptText())));

        ScreenplayGenerationResult parsed = parse(response);
        if (parsed.scenes() == null || parsed.scenes().isEmpty()) {
            throw PreProductionException.upstream("PRE_PROD_SCREENPLAY_GENERATE returned no scenes");
        }

        OffsetDateTime now = OffsetDateTime.now();
        Screenplay screenplay = screenplayRepository.findByProjectId(projectId).orElseGet(() -> Screenplay.builder()
                .tenantId(tenantId)
                .projectId(projectId)
                .scriptId(script.getId())
                .createdAt(now)
                .build());
        screenplay.setStatus(DraftStatus.DRAFT);
        screenplay.setUpdatedAt(now);
        screenplay = screenplayRepository.save(screenplay);

        List<ScreenplayScene> existing = screenplaySceneRepository.findByScreenplayIdOrderBySceneNumberAsc(screenplay.getId());
        screenplaySceneRepository.deleteAll(existing);
        UUID screenplayId = screenplay.getId();
        List<ScreenplayScene> scenes = parsed.scenes().stream()
                .map(item -> ScreenplayScene.builder()
                        .tenantId(tenantId)
                        .screenplayId(screenplayId)
                        .sceneNumber(item.sceneNumber())
                        .slug(item.slug())
                        .location(item.location())
                        .timeOfDay(TolerantEnumParser.parse(TimeOfDay.class, item.timeOfDay(), TimeOfDay.MIDDAY))
                        .summary(item.summary())
                        .characterFocus(item.characterFocus())
                        .emotionalPurpose(item.emotionalPurpose())
                        .createdAt(now)
                        .build())
                .map(screenplaySceneRepository::save)
                .collect(Collectors.toList());

        projectService.advanceStatus(tenantId, projectId, ProjectStatus.SCREENPLAY_READY);

        return toView(screenplay, scenes);
    }

    @Transactional(readOnly = true)
    public ScreenplayView get(UUID tenantId, UUID projectId) {
        Screenplay screenplay = screenplayRepository.findByProjectId(projectId)
                .orElseThrow(() -> PreProductionException.notFound("No screenplay for project " + projectId));
        return toView(screenplay, screenplaySceneRepository.findByScreenplayIdOrderBySceneNumberAsc(screenplay.getId()));
    }

    private ScreenplayGenerationResult parse(LlmGatewayChatResponse response) {
        if (response == null || response.response() == null || response.response().isBlank()) {
            throw PreProductionException.upstream("llm-gateway returned no content for PRE_PROD_SCREENPLAY_GENERATE");
        }
        try {
            return objectMapper.readValue(JsonExtraction.stripCodeFence(response.response()), ScreenplayGenerationResult.class);
        } catch (Exception ex) {
            throw PreProductionException.upstream("Could not parse PRE_PROD_SCREENPLAY_GENERATE response as JSON: " + ex.getMessage());
        }
    }

    private ScreenplayView toView(Screenplay screenplay, List<ScreenplayScene> scenes) {
        List<ScreenplaySceneView> sceneViews = scenes.stream()
                .map(s -> new ScreenplaySceneView(s.getId(), s.getSceneNumber(), s.getSlug(), s.getLocation(), s.getTimeOfDay(),
                        s.getSummary(), s.getCharacterFocus(), s.getEmotionalPurpose()))
                .collect(Collectors.toList());
        return new ScreenplayView(screenplay.getId(), screenplay.getProjectId(), screenplay.getScriptId(), screenplay.getStatus(), sceneViews);
    }
}
