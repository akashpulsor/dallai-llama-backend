package com.dalai.llama.creator.service.screenplayvideo;

import com.dalai.llama.creator.domain.entity.CreatorScriptShotPlan;
import com.dalai.llama.creator.repository.CreatorScriptShotPlanRepository;
import com.dalai.llama.creator.service.ProductionPlanTagService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Characterization tests for the two bugs this gateway exists to prevent from recurring - moved
 * here (out of ScreenplayVideoServicePhaseATest, no more reflection needed) once
 * attachProductionPlanTags/persistChatEditedTonePlan became this class's public attachTo()/
 * persistEditedTag() methods.
 *
 * <p>1. A scene-chat edit to anything other than expression/emotion/emotionIntensity/bodyLanguage
 * used to be silently reverted the next time the scene was generated, because the write side only
 * wrote those four fields back to CreatorScriptShotPlan while the read side unconditionally
 * re-read the whole storyboardTag from the DB on every generate/regenerate. Fixed by persisting
 * the AI edit's full next-state tag wholesale - see persistEditedTag()'s javadoc.
 *
 * <p>2. attachTo() derives scene fields from all three JSONB tags through typed views instead of
 * stringly-typed map.get("key") lookups - pinned here so a future edit can't silently reintroduce
 * a typo that gets swallowed as a blank string.
 */
class ShotPlanTagGatewayImplTest {

    @Test
    void persistEditedTag_persistsFullEditedTagNotJustToneFields() {
        CreatorScriptShotPlanRepository shotPlanRepository = mock(CreatorScriptShotPlanRepository.class);
        ShotPlanTagGatewayImpl gateway = new ShotPlanTagGatewayImpl(shotPlanRepository, new ObjectMapper());

        UUID scriptId = UUID.randomUUID();
        Map<String, Object> originalTag = new LinkedHashMap<>();
        originalTag.put("cameraAngle", "Wide shot");
        originalTag.put("expression", "neutral");
        CreatorScriptShotPlan existingPlan = CreatorScriptShotPlan.builder()
                .id(UUID.randomUUID())
                .scriptId(scriptId)
                .shotNumber(1)
                .styleKey(ProductionPlanTagService.DEFAULT_STYLE_KEY)
                .storyboardTag(originalTag)
                .build();
        when(shotPlanRepository.findByScriptIdAndShotNumberAndStyleKey(
                eq(scriptId), eq(1), eq(ProductionPlanTagService.DEFAULT_STYLE_KEY)
        )).thenReturn(Optional.of(existingPlan));

        // The chat edit changed a non-tone field (cameraAngle) - the AI edit result already
        // carries the full next-state tag (untouched fields preserved verbatim), per
        // SceneChatEditor's edit-prompt rules.
        Map<String, Object> editedTag = new LinkedHashMap<>();
        editedTag.put("cameraAngle", "Close-up");
        editedTag.put("expression", "neutral");
        Map<String, Object> editedScene = new LinkedHashMap<>();
        editedScene.put("shotNumber", 1);
        editedScene.put("storyboardTag", editedTag);

        gateway.persistEditedTag(scriptId, editedScene, 0);

        ArgumentCaptor<CreatorScriptShotPlan> savedPlan = ArgumentCaptor.forClass(CreatorScriptShotPlan.class);
        verify(shotPlanRepository).save(savedPlan.capture());
        assertEquals("Close-up", savedPlan.getValue().getStoryboardTag().get("cameraAngle"));
        assertEquals("neutral", savedPlan.getValue().getStoryboardTag().get("expression"));
    }

    @Test
    void attachTo_derivesSceneFieldsFromAllThreeTags() {
        ShotPlanTagGatewayImpl gateway = new ShotPlanTagGatewayImpl(mock(CreatorScriptShotPlanRepository.class), new ObjectMapper());

        Map<String, Object> storyboardTag = new LinkedHashMap<>();
        storyboardTag.put("productShotType", "Hero Shot");
        storyboardTag.put("keyLightSourceLabel", "North window");
        storyboardTag.put("lightingAtmosphericDescription", "Soft morning glow");
        storyboardTag.put("cameraAngle", "Eye level");
        storyboardTag.put("cameraMovement", "Slow push in");
        storyboardTag.put("lensSuggestion", "35mm");
        storyboardTag.put("compositionSummary", "Centered subject, rule of thirds background");
        storyboardTag.put("headroomNote", "Tight headroom");
        storyboardTag.put("frameLeftNote", "Negative space left");
        storyboardTag.put("frameRightNote", "Product visible right");
        storyboardTag.put("targetFocalPoint", "Model's eyes");
        storyboardTag.put("expression", "confident");
        storyboardTag.put("emotion", "joy");
        storyboardTag.put("emotionIntensity", 0.8);
        storyboardTag.put("bodyLanguage", "open posture");
        storyboardTag.put("narrativeBeatSummary", "The reveal");
        storyboardTag.put("setDesign", "Minimalist studio");
        storyboardTag.put("environment", "Indoor");
        storyboardTag.put("sceneTimeOfDay", "Morning");
        storyboardTag.put("culturalReferences", List.of("Diwali motif"));
        storyboardTag.put("transitionNote", "Hard cut in");
        storyboardTag.put("directorNote", "Keep energy high");
        storyboardTag.put("creatorTip", "Smile before the line");
        storyboardTag.put("ambientBedDescription", "Room tone");
        storyboardTag.put("syncHitDescription", "Whoosh on cut");
        Map<String, Object> character = new LinkedHashMap<>();
        character.put("storyCharacterName", "Riya");
        character.put("archetypeLabel", "Confident professional");
        character.put("distinguishingFeatures", "Curly hair");
        character.put("wardrobeThisShot", "Blue kurti");
        storyboardTag.put("primaryCharacters", List.of(character));

        Map<String, Object> lightingTag = new LinkedHashMap<>();
        lightingTag.put("cinematicIntent", "Warm and inviting");
        lightingTag.put("perspectiveView", Map.of("narrativeDescription", "Window light frames the subject"));
        Map<String, Object> keyLight = new LinkedHashMap<>();
        keyLight.put("role", "keyLight");
        keyLight.put("position", "45 degrees camera left");
        keyLight.put("modifier", "softbox");
        lightingTag.put("floorPlan", Map.of("keyLight", keyLight));

        Map<String, Object> cameraTag = new LinkedHashMap<>();
        cameraTag.put("movementSpec", Map.of("moveType", "Dolly", "speed", "Slow"));
        cameraTag.put("framePreview", Map.of("subjectPlacement", "Center", "captionPosition", "Bottom third"));
        cameraTag.put("coverageSpec", Map.of("editorIntent", "Cut on the reveal"));
        cameraTag.put("directorNote", "Hold the frame for a beat");

        CreatorScriptShotPlan plan = CreatorScriptShotPlan.builder()
                .storyboardTag(storyboardTag)
                .lightingBuildSheetTag(lightingTag)
                .cameraPlanSheetTag(cameraTag)
                .build();

        Map<String, Object> scene = new LinkedHashMap<>();
        scene.put("creatorDirection", "Existing note.");

        gateway.attachTo(plan, scene);

        assertEquals("Hero Shot", scene.get("productShotType"));
        assertEquals(
                "Key light: North window. Atmosphere: Soft morning glow. Intent: Warm and inviting. "
                        + "Lighting layout: Window light frames the subject. keyLight: 45 degrees camera left, softbox",
                scene.get("lighting")
        );
        assertEquals(
                "Angle: Eye level | Movement: Slow push in | Lens: 35mm | Composition: Centered subject, rule of thirds background"
                        + " | Headroom: Tight headroom | Frame left: Negative space left | Frame right: Product visible right"
                        + " | Focal point: Model's eyes | Move type: Dolly | Move speed: Slow | Subject placement: Center"
                        + " | Caption position: Bottom third | Editor intent: Cut on the reveal",
                scene.get("camera")
        );
        assertEquals("Expression: confident. Emotion: joy. Intensity: 0.8. Body language: open posture", scene.get("emotionalDirection"));
        assertEquals("The reveal", scene.get("narrativeBeat"));
        assertEquals("Minimalist studio. Indoor. Morning. Diwali motif", scene.get("backgroundDetail"));
        assertEquals("Existing note.. Hard cut in. Keep energy high. Smile before the line. Hold the frame for a beat", scene.get("creatorDirection"));
        assertEquals("Room tone. Whoosh on cut", scene.get("audioDescription"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> characterDetail = (List<Map<String, Object>>) scene.get("characterDetail");
        assertEquals("Riya", characterDetail.get(0).get("name"));
        assertEquals("Blue kurti", characterDetail.get(0).get("wardrobeThisShot"));
    }

    @Test
    void attachTo_noOpWhenPlanOrSceneIsNull() {
        ShotPlanTagGatewayImpl gateway = new ShotPlanTagGatewayImpl(mock(CreatorScriptShotPlanRepository.class), new ObjectMapper());
        Map<String, Object> scene = new LinkedHashMap<>();
        gateway.attachTo(null, scene);
        assertFalse(scene.containsKey("storyboardTag"));
    }
}
