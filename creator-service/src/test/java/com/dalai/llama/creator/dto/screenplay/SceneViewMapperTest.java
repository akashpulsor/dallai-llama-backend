package com.dalai.llama.creator.dto.screenplay;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Characterization tests for ScreenplaySceneView/SceneViewMapper - in particular, that every
 * @JsonAlias collapses the confirmed duplicate spelling correctly, and that the empty/malformed
 * fallback path never throws for migrated call sites.
 */
class SceneViewMapperTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void sceneView_returnsEmptyViewForNullOrEmptyMap() {
        assertNotNull(SceneViewMapper.sceneView(null, objectMapper));
        assertNotNull(SceneViewMapper.sceneView(Map.of(), objectMapper));
        assertEquals("", SceneViewMapper.sceneView(null, objectMapper).id());
        assertEquals("", SceneViewMapper.sceneView(Map.of(), objectMapper).title());
    }

    @Test
    void sceneView_readsPlainFieldsDirectly() {
        Map<String, Object> raw = Map.of(
                "id", "scene-1",
                "sceneNumber", 3,
                "status", "PLANNED",
                "prompt", "A calm morning scene."
        );
        ScreenplaySceneView view = SceneViewMapper.sceneView(raw, objectMapper);
        assertEquals("scene-1", view.id());
        assertEquals(3, view.sceneNumber());
        assertEquals("PLANNED", view.status());
        assertEquals("A calm morning scene.", view.prompt());
    }

    @Test
    void sceneView_idAliasesSceneIdAndSceneIdSnakeCase() {
        assertEquals("from-id", SceneViewMapper.sceneView(Map.of("id", "from-id"), objectMapper).id());
        assertEquals("from-sceneId", SceneViewMapper.sceneView(Map.of("sceneId", "from-sceneId"), objectMapper).id());
        assertEquals("from-scene_id", SceneViewMapper.sceneView(Map.of("scene_id", "from-scene_id"), objectMapper).id());
    }

    @Test
    void sceneView_titleAliasesBeatTitle() {
        assertEquals("Beat Title Value", SceneViewMapper.sceneView(Map.of("beatTitle", "Beat Title Value"), objectMapper).title());
    }

    @Test
    void sceneView_durationSecondsAliasesSnakeCaseAndBareDuration() {
        assertEquals(5, SceneViewMapper.sceneView(Map.of("duration_seconds", 5), objectMapper).durationSeconds());
        assertEquals(7, SceneViewMapper.sceneView(Map.of("duration", 7), objectMapper).durationSeconds());
    }

    @Test
    void sceneView_videoDirectorPlanAliasesAllFourSpellings() {
        Map<String, Object> plan = Map.of("generationPrompt", "move slowly");
        assertEquals(plan, SceneViewMapper.sceneView(Map.of("videoDirectorPlan", plan), objectMapper).videoDirectorPlan());
        assertEquals(plan, SceneViewMapper.sceneView(Map.of("video_director_plan", plan), objectMapper).videoDirectorPlan());
        assertEquals(plan, SceneViewMapper.sceneView(Map.of("directorPlan", plan), objectMapper).videoDirectorPlan());
        assertEquals(plan, SceneViewMapper.sceneView(Map.of("director_plan", plan), objectMapper).videoDirectorPlan());
    }

    @Test
    void sceneView_retentionGoalAndPatternInterruptAliasSnakeCase() {
        assertEquals("hook them", SceneViewMapper.sceneView(Map.of("retention_goal", "hook them"), objectMapper).retentionGoal());
        assertEquals("sudden cut", SceneViewMapper.sceneView(Map.of("pattern_interrupt", "sudden cut"), objectMapper).patternInterrupt());
    }

    @Test
    void sceneView_cameraMovementAliasesCameraMoveAndMotion() {
        assertEquals("dolly in", SceneViewMapper.sceneView(Map.of("cameraMovement", "dolly in"), objectMapper).cameraMovement());
        assertEquals("pan left", SceneViewMapper.sceneView(Map.of("cameraMove", "pan left"), objectMapper).cameraMovement());
        assertEquals("zoom", SceneViewMapper.sceneView(Map.of("motion", "zoom"), objectMapper).cameraMovement());
    }

    @Test
    void sceneView_negativePromptAliasesSnakeCase() {
        assertEquals("no watermark", SceneViewMapper.sceneView(Map.of("negative_prompt", "no watermark"), objectMapper).negativePrompt());
    }

    @Test
    void sceneView_dialogueCloneMethodAliasesDialogueCloneVoiceModel() {
        assertEquals("fal_minimax_voice_clone", SceneViewMapper.sceneView(Map.of("dialogueCloneVoiceModel", "fal_minimax_voice_clone"), objectMapper).dialogueCloneMethod());
    }

    @Test
    void sceneView_productShotPlanAliasesSnakeCase() {
        Map<String, Object> plan = Map.of("shotType", "hero product shot");
        assertEquals(plan, SceneViewMapper.sceneView(Map.of("product_shot_plan", plan), objectMapper).productShotPlan());
    }

    @Test
    void sceneView_readsNestedMapsAndListsAndUnknownFieldsAreIgnored() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("storyboardTag", Map.of("cameraAngle", "wide"));
        raw.put("productImageAssets", List.of(Map.of("bucket", "b", "objectKey", "k")));
        raw.put("frameIds", List.of("f1", "f2"));
        raw.put("someBrandNewFieldFromAFutureAiProviderIntegration", "should not break parsing");

        ScreenplaySceneView view = SceneViewMapper.sceneView(raw, objectMapper);
        assertEquals("wide", view.storyboardTag().get("cameraAngle"));
        assertEquals(1, view.productImageAssets().size());
        assertEquals(List.of("f1", "f2"), view.frameIds());
    }

    @Test
    void sceneView_booleanFieldsCoerceCorrectly() {
        Map<String, Object> raw = Map.of("noHumans", true, "dialogueCloneAccepted", false);
        ScreenplaySceneView view = SceneViewMapper.sceneView(raw, objectMapper);
        assertTrue(view.noHumansOrFalse());
        assertTrue(!view.dialogueCloneAcceptedOrFalse());
    }
}
