package com.dalai.llama.creator.service;

import com.dalai.llama.creator.domain.entity.CreatorGenerationJob;
import com.dalai.llama.creator.domain.entity.CreatorScript;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Characterization tests for SceneRegenerationJobStarter - the extraction of
 * ScreenplayVideoService.startRegenerateSceneJob(), zero coverage before this move.
 */
class SceneRegenerationJobStarterTest {

    private final ScreenplayVideoService owner = mock(ScreenplayVideoService.class);
    private final GenerationJobService generationJobService = mock(GenerationJobService.class);
    private final SceneRegenerationJobStarter starter = new SceneRegenerationJobStarter(owner, generationJobService);

    @Test
    void throwsConflict_whenAnotherSceneIsAlreadyGenerating() {
        Map<String, Object> generatingScene = Map.of("id", "scene-2", "status", "GENERATING_VIDEO");
        Map<String, Object> run = new LinkedHashMap<>();
        run.put("scenes", List.of(Map.of("id", "scene-1", "status", "READY"), generatingScene));
        when(owner.loadRun(any(), any(), any())).thenReturn(runRecord(run));
        when(owner.loadScript(any(), any(), any())).thenReturn(script());
        when(owner.enrichScenesWithStoryboardReferences(any(), any(), any()))
                .thenReturn(List.of(Map.of("id", "scene-1", "status", "READY"), generatingScene));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                starter.startRegenerateSceneJob(UUID.randomUUID(), "scene-1", Map.of(), "tenant", "user"));
        assertTrue(ex.getReason().contains("Another shot is already generating"));
    }

    @Test
    void throwsConflict_whenReplacingExistingClipWithoutBillingConsent() {
        Map<String, Object> sceneWithClip = new LinkedHashMap<>();
        sceneWithClip.put("id", "scene-1");
        sceneWithClip.put("bucket", "b");
        sceneWithClip.put("objectKey", "k");
        Map<String, Object> run = new LinkedHashMap<>();
        run.put("scenes", List.of(sceneWithClip));
        when(owner.loadRun(any(), any(), any())).thenReturn(runRecord(run));
        when(owner.loadScript(any(), any(), any())).thenReturn(script());
        when(owner.enrichScenesWithStoryboardReferences(any(), any(), any())).thenReturn(List.of(sceneWithClip));
        when(owner.findSceneIndex(any(), any())).thenReturn(0);
        when(owner.generationModeFor(any(), any(), any())).thenReturn("ai_generated");
        when(owner.providerForSceneGeneration(any(), any(), any())).thenReturn("seedance");
        when(owner.modelForSceneGeneration(any(), any(), any(), any())).thenReturn("bytedance/seedance-2.0");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                starter.startRegenerateSceneJob(UUID.randomUUID(), "scene-1", Map.of(), "tenant", "user"));
        assertTrue(ex.getReason().contains("paid video-model run"));
    }

    @Test
    void queuesJobAndReturnsUpdatedProgress_onGoldenPath() {
        Map<String, Object> scene = new LinkedHashMap<>();
        scene.put("id", "scene-1");
        scene.put("sceneNumber", 1);
        Map<String, Object> run = new LinkedHashMap<>();
        run.put("scenes", List.of(scene));
        when(owner.loadRun(any(), any(), any())).thenReturn(runRecord(run));
        when(owner.loadScript(any(), any(), any())).thenReturn(script());
        when(owner.enrichScenesWithStoryboardReferences(any(), any(), any())).thenReturn(new java.util.ArrayList<>(List.of(scene)));
        when(owner.findSceneIndex(any(), any())).thenReturn(0);
        when(owner.generationModeFor(any(), any(), any())).thenReturn("ai_generated");
        when(owner.providerForSceneGeneration(any(), any(), any())).thenReturn("seedance");
        when(owner.modelForSceneGeneration(any(), any(), any(), any())).thenReturn("bytedance/seedance-2.0");
        when(owner.outputPayload(any(), anyString())).thenReturn(Map.of("videoRun", run));

        CreatorGenerationJob startedJob = new CreatorGenerationJob();
        startedJob.setId(UUID.randomUUID());
        when(generationJobService.startGenerationJob(anyString(), anyString(), anyString(), any(), any())).thenReturn(startedJob);
        CreatorGenerationJob updatedJob = new CreatorGenerationJob();
        updatedJob.setId(startedJob.getId());
        when(generationJobService.updateGenerationJobProgress(any(), anyInt(), anyString(), any())).thenReturn(updatedJob);

        CreatorGenerationJob result = starter.startRegenerateSceneJob(UUID.randomUUID(), "scene-1", Map.of(), "tenant", "user");

        assertEquals(startedJob.getId(), result.getId());
    }

    private ScreenplayVideoService.RunRecord runRecord(Map<String, Object> run) {
        run.putIfAbsent("scriptId", UUID.randomUUID().toString());
        return new ScreenplayVideoService.RunRecord(null, run);
    }

    private CreatorScript script() {
        return CreatorScript.builder()
                .id(UUID.randomUUID())
                .tenantId("tenant")
                .userId("user")
                .title("Founder script")
                .scriptPayload(new LinkedHashMap<>())
                .shots(List.of())
                .status("GENERATED")
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .build();
    }
}
