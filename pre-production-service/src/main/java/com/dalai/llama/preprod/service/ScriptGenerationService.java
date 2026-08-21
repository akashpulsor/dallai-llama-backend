package com.dalai.llama.preprod.service;

import com.dalai.llama.preprod.domain.DraftStatus;
import com.dalai.llama.preprod.domain.ProjectStatus;
import com.dalai.llama.preprod.domain.entity.Script;
import com.dalai.llama.preprod.domain.entity.ScriptCharacter;
import com.dalai.llama.preprod.dto.GenerateScriptRequest;
import com.dalai.llama.preprod.dto.ScriptCharacterView;
import com.dalai.llama.preprod.dto.ScriptView;
import com.dalai.llama.preprod.repository.ProjectRepository;
import com.dalai.llama.preprod.repository.ScriptCharacterRepository;
import com.dalai.llama.preprod.repository.ScriptRepository;
import com.dalai.llama.preprod.service.generation.JsonExtraction;
import com.dalai.llama.preprod.service.generation.ScriptGenerationResult;
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
public class ScriptGenerationService {

    private static final String TASK_KEY = "PRE_PROD_SCRIPT_GENERATE";

    private final ProjectRepository projectRepository;
    private final ScriptRepository scriptRepository;
    private final ScriptCharacterRepository scriptCharacterRepository;
    private final LlmGatewayClient llmGatewayClient;
    private final ObjectMapper objectMapper;
    private final ProjectService projectService;
    private final String defaultModel;

    public ScriptGenerationService(
            ProjectRepository projectRepository,
            ScriptRepository scriptRepository,
            ScriptCharacterRepository scriptCharacterRepository,
            LlmGatewayClient llmGatewayClient,
            ObjectMapper objectMapper,
            ProjectService projectService,
            @Value("${pre-production.llm-gateway.default-text-model}") String defaultModel
    ) {
        this.projectRepository = projectRepository;
        this.scriptRepository = scriptRepository;
        this.scriptCharacterRepository = scriptCharacterRepository;
        this.llmGatewayClient = llmGatewayClient;
        this.objectMapper = objectMapper;
        this.projectService = projectService;
        this.defaultModel = defaultModel;
    }

    @Transactional
    public ScriptView generate(UUID tenantId, UUID projectId, GenerateScriptRequest request) {
        if (projectRepository.findByIdAndTenantId(projectId, tenantId).isEmpty()) {
            throw PreProductionException.notFound("No project " + projectId);
        }

        Map<String, String> variables = Map.of(
                "brief", request.briefText(),
                "durationSeconds", String.valueOf(request.targetDurationSeconds() == null ? 60 : request.targetDurationSeconds())
        );
        LlmGatewayChatResponse response = llmGatewayClient.chat(
                tenantId.toString(),
                "script-generate-" + projectId,
                new LlmGatewayChatRequest(defaultModel, List.of(new LlmGatewayMessage("user", "")),
                        JsonExtraction.JSON_MODE_PARAMS, TASK_KEY, variables));

        ScriptGenerationResult parsed = parse(response);
        if (parsed.scriptText() == null || parsed.scriptText().isBlank()) {
            throw PreProductionException.upstream("PRE_PROD_SCRIPT_GENERATE returned no scriptText");
        }

        OffsetDateTime now = OffsetDateTime.now();
        Script script = scriptRepository.findByProjectId(projectId).orElseGet(() -> Script.builder()
                .tenantId(tenantId)
                .projectId(projectId)
                .createdAt(now)
                .build());
        script.setStatus(DraftStatus.DRAFT);
        script.setScriptText(parsed.scriptText());
        script.setPacingStyle(parsed.pacingStyle());
        script.setEmotionalArc(parsed.emotionalArc());
        script.setHookStrategy(parsed.hookStrategy());
        script.setUpdatedAt(now);
        script = scriptRepository.save(script);
        UUID scriptId = script.getId();

        List<ScriptCharacter> characters = (parsed.characters() == null ? List.<ScriptGenerationResult.CharacterItem>of() : parsed.characters())
                .stream()
                .map(item -> upsertCharacter(tenantId, scriptId, item, now))
                .collect(Collectors.toList());

        projectService.advanceStatus(tenantId, projectId, ProjectStatus.SCRIPT_READY);

        return toView(script, characters);
    }

    @Transactional(readOnly = true)
    public ScriptView get(UUID tenantId, UUID projectId) {
        Script script = scriptRepository.findByProjectId(projectId)
                .orElseThrow(() -> PreProductionException.notFound("No script for project " + projectId));
        return toView(script, scriptCharacterRepository.findByScriptId(script.getId()));
    }

    private ScriptCharacter upsertCharacter(UUID tenantId, UUID scriptId, ScriptGenerationResult.CharacterItem item, OffsetDateTime now) {
        ScriptCharacter character = scriptCharacterRepository.findByScriptIdAndCharacterKey(scriptId, item.characterKey())
                .orElseGet(() -> ScriptCharacter.builder()
                        .tenantId(tenantId)
                        .scriptId(scriptId)
                        .characterKey(item.characterKey())
                        .createdAt(now)
                        .build());
        character.setCharacterName(item.characterName());
        character.setCharacterRole(item.characterRole());
        character.setDescription(item.description());
        return scriptCharacterRepository.save(character);
    }

    private ScriptGenerationResult parse(LlmGatewayChatResponse response) {
        if (response == null || response.response() == null || response.response().isBlank()) {
            throw PreProductionException.upstream("llm-gateway returned no content for PRE_PROD_SCRIPT_GENERATE");
        }
        try {
            return objectMapper.readValue(JsonExtraction.stripCodeFence(response.response()), ScriptGenerationResult.class);
        } catch (Exception ex) {
            throw PreProductionException.upstream("Could not parse PRE_PROD_SCRIPT_GENERATE response as JSON: " + ex.getMessage());
        }
    }

    private ScriptView toView(Script script, List<ScriptCharacter> characters) {
        List<ScriptCharacterView> characterViews = characters.stream()
                .map(c -> new ScriptCharacterView(c.getId(), c.getCharacterKey(), c.getCharacterName(), c.getCharacterRole(), c.getDescription()))
                .collect(Collectors.toList());
        return new ScriptView(script.getId(), script.getProjectId(), script.getStatus(), script.getScriptText(),
                script.getPacingStyle(), script.getEmotionalArc(), script.getHookStrategy(), characterViews);
    }
}
