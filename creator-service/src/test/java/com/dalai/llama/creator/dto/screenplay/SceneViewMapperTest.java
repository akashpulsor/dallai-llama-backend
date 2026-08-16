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
    void sceneView_dialogueFieldAcceptsStructuredMapOrListShapesWithoutFallingBackToEmpty() {
        // Regression test: "dialogue" is polymorphic in real scene data - ScreenplayVideoService's
        // dialogueObjectText walks it as a scalar, Map, or Collection. If this field were typed as
        // String, any of these structured shapes would throw during Jackson conversion and the
        // whole scene would silently collapse to the EMPTY fallback view.
        Map<String, Object> structuredDialogue = Map.of("text", "Hello there.", "voice", "warm");
        ScreenplaySceneView mapShape = SceneViewMapper.sceneView(Map.of("id", "scene-1", "dialogue", structuredDialogue), objectMapper);
        assertEquals("scene-1", mapShape.id());
        assertEquals(structuredDialogue, mapShape.dialogue());

        List<Map<String, Object>> listDialogue = List.of(Map.of("text", "Line one."), Map.of("text", "Line two."));
        ScreenplaySceneView listShape = SceneViewMapper.sceneView(Map.of("id", "scene-2", "dialogue", listDialogue), objectMapper);
        assertEquals("scene-2", listShape.id());
        assertEquals(listDialogue, listShape.dialogue());

        ScreenplaySceneView scalarShape = SceneViewMapper.sceneView(Map.of("id", "scene-3", "dialogue", "Plain line."), objectMapper);
        assertEquals("Plain line.", scalarShape.dialogue());
    }

    @Test
    void sceneView_readsDialogueTextFallbackFieldsSeparately() {
        Map<String, Object> raw = Map.of(
                "exactDialogue", "exact value",
                "spokenDialogue", "spoken value",
                "voiceover", "voiceover value",
                "voiceOver", "voiceOver value",
                "narration", "narration value",
                "spokenLine", "spokenLine value"
        );
        ScreenplaySceneView view = SceneViewMapper.sceneView(raw, objectMapper);
        assertEquals("exact value", view.exactDialogue());
        assertEquals("spoken value", view.spokenDialogue());
        assertEquals("voiceover value", view.voiceover());
        assertEquals("voiceOver value", view.voiceOver());
        assertEquals("narration value", view.narration());
        assertEquals("spokenLine value", view.spokenLine());
    }

    @Test
    void sceneView_readsGenerationModeAndAssetCaptureModeFallbackFieldsSeparately() {
        Map<String, Object> raw = Map.of(
                "generationMode", "ai_generated",
                "generation_mode", "snake case value",
                "assetCaptureMode", "camel case value",
                "asset_capture_mode", "snake case capture value"
        );
        ScreenplaySceneView view = SceneViewMapper.sceneView(raw, objectMapper);
        assertEquals("ai_generated", view.generationMode());
        assertEquals("snake case value", view.generationModeSnakeCase());
        assertEquals("camel case value", view.assetCaptureMode());
        assertEquals("snake case capture value", view.assetCaptureModeSnakeCase());
    }

    @Test
    void sceneView_readsAvatarProviderModeAndAvatarProviderSeparately() {
        Map<String, Object> raw = Map.of("avatarProviderMode", "mode value", "avatarProvider", "provider value");
        ScreenplaySceneView view = SceneViewMapper.sceneView(raw, objectMapper);
        assertEquals("mode value", view.avatarProviderMode());
        assertEquals("provider value", view.avatarProvider());
    }

    @Test
    void sceneView_booleanFieldsCoerceCorrectly() {
        Map<String, Object> raw = Map.of("noHumans", true, "dialogueCloneAccepted", false);
        ScreenplaySceneView view = SceneViewMapper.sceneView(raw, objectMapper);
        assertTrue(view.noHumansOrFalse());
        assertTrue(!view.dialogueCloneAcceptedOrFalse());
    }
}
