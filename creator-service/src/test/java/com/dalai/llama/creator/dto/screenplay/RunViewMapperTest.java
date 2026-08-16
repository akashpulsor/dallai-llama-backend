package com.dalai.llama.creator.dto.screenplay;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Characterization tests for ScreenplayRunView/RunViewMapper - mirrors SceneViewMapperTest's
 * rigor for the Run-level envelope, including that nested scenes parse through
 * ScreenplaySceneView correctly.
 */
class RunViewMapperTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void runView_returnsEmptyViewForNullOrEmptyMap() {
        assertNotNull(RunViewMapper.runView(null, objectMapper));
        assertEquals("", RunViewMapper.runView(Map.of(), objectMapper).runId());
        assertEquals("", RunViewMapper.runView(null, objectMapper).status());
    }

    @Test
    void runView_readsPlainFieldsDirectly() {
        Map<String, Object> raw = Map.of(
                "runId", "run-1",
                "scriptId", "script-1",
                "status", "PLANNED",
                "provider", "dalai_llama"
        );
        ScreenplayRunView view = RunViewMapper.runView(raw, objectMapper);
        assertEquals("run-1", view.runId());
        assertEquals("script-1", view.scriptId());
        assertEquals("PLANNED", view.status());
        assertEquals("dalai_llama", view.provider());
    }

    @Test
    void runView_runIdAliasesId() {
        assertEquals("from-id", RunViewMapper.runView(Map.of("id", "from-id"), objectMapper).runId());
    }

    @Test
    void runView_scenesAliasesSceneClips() {
        Map<String, Object> scene = Map.of("id", "scene-1", "sceneNumber", 1);
        assertEquals(1, RunViewMapper.runView(Map.of("scenes", List.of(scene)), objectMapper).scenes().size());
        assertEquals(1, RunViewMapper.runView(Map.of("sceneClips", List.of(scene)), objectMapper).scenes().size());
    }

    @Test
    void runView_nestedScenesParseAsTypedSceneViews() {
        Map<String, Object> scene = Map.of("id", "scene-7", "sceneNumber", 7, "dialogueScript", "Hello there.");
        ScreenplayRunView view = RunViewMapper.runView(Map.of("scenes", List.of(scene)), objectMapper);
        ScreenplaySceneView sceneView = view.scenes().get(0);
        assertEquals("scene-7", sceneView.id());
        assertEquals(7, sceneView.sceneNumber());
        assertEquals("Hello there.", sceneView.dialogueScript());
    }

    @Test
    void runView_founderAvatarProfileAliasesFounderKit() {
        Map<String, Object> profile = Map.of("avatarId", "avatar-1");
        assertEquals(profile, RunViewMapper.runView(Map.of("founderKit", profile), objectMapper).founderAvatarProfile());
    }

    @Test
    void runView_avatarProviderModeAliasesAvatarProvider() {
        assertEquals("dalai_llama", RunViewMapper.runView(Map.of("avatarProvider", "dalai_llama"), objectMapper).avatarProviderMode());
    }

    @Test
    void runView_editingPlanAliasesEditorHandoffPlan() {
        Map<String, Object> plan = Map.of("recommendedTools", List.of("premiere"));
        assertEquals(plan, RunViewMapper.runView(Map.of("editorHandoffPlan", plan), objectMapper).editingPlan());
    }

    @Test
    void runView_combinedDialogueAudioAliasesCombinedSceneDialogueAudio() {
        Map<String, Object> asset = Map.of("objectKey", "combined.m4a");
        assertEquals(asset, RunViewMapper.runView(Map.of("combinedSceneDialogueAudio", asset), objectMapper).combinedDialogueAudio());
    }

    @Test
    void runView_videoUrlAliasesPublicUrlAndFinalVideoUrl() {
        assertEquals("https://cdn/a.mp4", RunViewMapper.runView(Map.of("videoUrl", "https://cdn/a.mp4"), objectMapper).videoUrl());
        assertEquals("https://cdn/b.mp4", RunViewMapper.runView(Map.of("publicUrl", "https://cdn/b.mp4"), objectMapper).videoUrl());
        assertEquals("https://cdn/c.mp4", RunViewMapper.runView(Map.of("finalVideoUrl", "https://cdn/c.mp4"), objectMapper).videoUrl());
    }

    @Test
    void runView_unknownFieldsAreIgnoredAndBooleansCoerce() {
        Map<String, Object> raw = Map.of(
                "noHumans", true,
                "someFutureFieldNotYetModeled", Map.of("nested", "value")
        );
        ScreenplayRunView view = RunViewMapper.runView(raw, objectMapper);
        assertTrue(view.noHumansOrFalse());
    }
}
