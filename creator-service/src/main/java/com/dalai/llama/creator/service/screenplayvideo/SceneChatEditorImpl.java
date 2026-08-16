package com.dalai.llama.creator.service.screenplayvideo;

import com.dalai.llama.creator.domain.PromptTemplateType;
import com.dalai.llama.creator.domain.entity.CreatorPromptRun;
import com.dalai.llama.creator.domain.entity.CreatorScript;
import com.dalai.llama.creator.repository.CreatorPromptRunRepository;
import com.dalai.llama.creator.service.CreatorAiService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.dalai.llama.creator.service.screenplayvideo.MapCoercion.copyMap;
import static com.dalai.llama.creator.service.screenplayvideo.MapCoercion.firstNonEmptyMap;
import static com.dalai.llama.creator.service.screenplayvideo.MapCoercion.firstText;
import static com.dalai.llama.creator.service.screenplayvideo.MapCoercion.mapValue;

@Component
public class SceneChatEditorImpl implements SceneChatEditor {

    private static final Logger log = LoggerFactory.getLogger(SceneChatEditorImpl.class);

    private final CreatorAiService creatorAiService;
    private final CreatorPromptRunRepository promptRunRepository;

    public SceneChatEditorImpl(CreatorAiService creatorAiService, CreatorPromptRunRepository promptRunRepository) {
        this.creatorAiService = creatorAiService;
        this.promptRunRepository = promptRunRepository;
    }

    @Override
    public SceneEditResult generateEditedScene(
            CreatorScript script,
            Map<String, Object> scene,
            String message,
            Map<String, Object> ragContext,
            String renderedPrompt,
            UUID jobId
    ) {
        Map<String, Object> providerInput = new LinkedHashMap<>();
        providerInput.put("scriptId", script.getId().toString());
        providerInput.put("projectId", script.getProjectId() == null ? null : script.getProjectId().toString());
        providerInput.put("instruction", message);
        providerInput.put("shot", scene);
        providerInput.put("scene", scene);
        providerInput.put("ragContext", ragContext);
        providerInput.put("renderedPrompt", renderedPrompt);

        try {
            CreatorAiService.AiUsageContext usageContext = new CreatorAiService.AiUsageContext(
                    script.getTenantId(),
                    script.getUserId(),
                    script.getProjectId(),
                    jobId,
                    null
            );
            CreatorAiService.MeteredAiResponse aiResponse = creatorAiService.generateMetered(
                    PromptTemplateType.SHOT_JSON_EDIT.name(),
                    providerInput,
                    usageContext
            );
            Map<String, Object> providerOutput = copyMap(aiResponse.output());
            Map<String, Object> editedScene = firstNonEmptyMap(
                    providerOutput.get("scene"),
                    providerOutput.get("shot"),
                    providerOutput.get("updatedScene"),
                    providerOutput.get("updatedShot")
            );
            if (editedScene.isEmpty()) {
                editedScene = fallbackEditedScene(scene, message, ragContext);
            }
            CreatorPromptRun promptRun = promptRunRepository.save(CreatorPromptRun.builder()
                    .tenantId(script.getTenantId())
                    .userId(script.getUserId())
                    .projectId(script.getProjectId())
                    .jobId(jobId)
                    .promptTemplateKey(PromptTemplateType.SHOT_JSON_EDIT.name())
                    .promptTemplateVersion(1)
                    .renderedPrompt(renderedPrompt)
                    .inputSnapshot(providerInput)
                    .provider(creatorAiService.providerName())
                    .model(creatorAiService.modelName())
                    .outputPayload(providerOutput)
                    .tokenMetadata(aiResponse.tokenMetadata())
                    .costMetadata(aiResponse.costMetadata())
                    .status("COMPLETED")
                    .completedAt(OffsetDateTime.now())
                    .build());
            creatorAiService.publishBillingDebit(
                    PromptTemplateType.SHOT_JSON_EDIT.name(),
                    aiResponse,
                    usageContext.withPromptRunId(promptRun.getId())
            );
            return new SceneEditResult(editedScene, promptRun.getId(), providerOutput, null);
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            log.warn("Scene AI edit failed scriptId={} sceneId={} errorType={} errorMessage={}",
                    script.getId(),
                    scene.get("id"),
                    ex.getClass().getSimpleName(),
                    ex.getMessage());
            return new SceneEditResult(fallbackEditedScene(scene, message, ragContext), null, Map.of(), ex.getMessage());
        }
    }

    private Map<String, Object> fallbackEditedScene(Map<String, Object> scene, String message, Map<String, Object> ragContext) {
        Map<String, Object> edited = new LinkedHashMap<>(scene);
        String existingPrompt = firstText(scene.get("providerPrompt"), scene.get("seedancePrompt"), scene.get("prompt"), scene.get("action"));
        String continuity = firstText(
                mapValue(ragContext.get("videoConsistencyBible")).get("globalConsistencyPrompt"),
                mapValue(ragContext.get("seedancePromptStrategy")).get("globalConsistencyPrompt"),
                "Preserve characters, wardrobe, location continuity, captions, pacing, and shot order."
        );
        String revisedPrompt = """
                %s

                Revision request: %s
                Continuity lock: %s
                Keep the same timeline duration, aspect ratio, captions, and adjacent-scene continuity.
                """.formatted(existingPrompt, message, continuity).trim();
        edited.put("providerPrompt", revisedPrompt);
        edited.put("prompt", revisedPrompt);
        edited.put("action", firstText(scene.get("action"), scene.get("description"), "") + " Revision: " + message);
        edited.put("editingNotes", List.of("RAG fallback edit applied: " + message));
        return edited;
    }
}
