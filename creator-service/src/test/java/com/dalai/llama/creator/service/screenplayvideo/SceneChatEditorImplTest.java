package com.dalai.llama.creator.service.screenplayvideo;

import com.dalai.llama.creator.domain.entity.CreatorPromptRun;
import com.dalai.llama.creator.domain.entity.CreatorScript;
import com.dalai.llama.creator.repository.CreatorPromptRunRepository;
import com.dalai.llama.creator.service.CreatorAiService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Characterization tests for SceneChatEditorImpl - this class had zero coverage before this
 * session despite being where every scene-chat-edit AI call actually happens. Covers both the
 * happy path (AI returns a usable edited scene) and the failure/fallback path (AI call throws,
 * a text-only revision is still applied instead of losing the user's request) - the failure path
 * specifically is what ScreenplayVideoService.chatScene()'s "fail the job loudly" fix depends on
 * (SceneEditResult.failed() must be true so the caller knows to call failGenerationJob).
 */
class SceneChatEditorImplTest {

    private static CreatorScript script() {
        return CreatorScript.builder()
                .id(UUID.randomUUID())
                .tenantId("tenant-1")
                .userId("user-1")
                .projectId(UUID.randomUUID())
                .build();
    }

    @Test
    void generateEditedScene_returnsAiEditedSceneOnSuccess() {
        CreatorAiService creatorAiService = mock(CreatorAiService.class);
        CreatorPromptRunRepository promptRunRepository = mock(CreatorPromptRunRepository.class);
        SceneChatEditorImpl editor = new SceneChatEditorImpl(creatorAiService, promptRunRepository);

        Map<String, Object> editedSceneFromAi = new LinkedHashMap<>();
        editedSceneFromAi.put("action", "Riya smiles confidently at the camera.");
        Map<String, Object> aiOutput = Map.of("scene", editedSceneFromAi);
        CreatorAiService.MeteredAiResponse aiResponse = new CreatorAiService.MeteredAiResponse(
                aiOutput, Map.of(), Map.of(), UUID.randomUUID(), BigDecimal.ZERO
        );
        when(creatorAiService.generateMetered(anyString(), any(), any())).thenReturn(aiResponse);
        when(creatorAiService.providerName()).thenReturn("gemini");
        when(creatorAiService.modelName()).thenReturn("gemini-2.5-flash");
        UUID promptRunId = UUID.randomUUID();
        when(promptRunRepository.save(any())).thenAnswer(invocation -> {
            CreatorPromptRun run = invocation.getArgument(0);
            run.setId(promptRunId);
            return run;
        });

        Map<String, Object> scene = new LinkedHashMap<>();
        scene.put("id", "scene-1");
        scene.put("action", "Riya looks at the camera.");

        SceneEditResult result = editor.generateEditedScene(
                script(), scene, "make her more confident", Map.of("videoConsistencyBible", Map.of()), "rendered prompt", UUID.randomUUID()
        );

        assertFalse(result.failed());
        assertNull(result.errorMessage());
        assertEquals(promptRunId, result.promptRunId());
        assertEquals("Riya smiles confidently at the camera.", result.scene().get("action"));
    }

    @Test
    void generateEditedScene_fallsBackToTextRevisionWhenAiCallThrows() {
        CreatorAiService creatorAiService = mock(CreatorAiService.class);
        CreatorPromptRunRepository promptRunRepository = mock(CreatorPromptRunRepository.class);
        SceneChatEditorImpl editor = new SceneChatEditorImpl(creatorAiService, promptRunRepository);

        when(creatorAiService.generateMetered(anyString(), any(), any()))
                .thenThrow(new RuntimeException("AI provider unavailable"));

        Map<String, Object> scene = new LinkedHashMap<>();
        scene.put("id", "scene-1");
        scene.put("action", "Riya looks at the camera.");
        scene.put("prompt", "Riya looks at the camera.");

        SceneEditResult result = editor.generateEditedScene(
                script(), scene, "make her more confident", Map.of(), "rendered prompt", UUID.randomUUID()
        );

        assertTrue(result.failed(), "a thrown AI call must be surfaced as a failed result, not silently swallowed");
        assertEquals("AI provider unavailable", result.errorMessage());
        assertNull(result.promptRunId());
        // The scene still gets a usable (if generic) revision applied - the user's request isn't lost.
        assertNotNull(result.scene().get("prompt"));
        assertTrue(String.valueOf(result.scene().get("prompt")).contains("make her more confident"));
        assertTrue(String.valueOf(result.scene().get("action")).contains("Revision: make her more confident"));
    }
}
