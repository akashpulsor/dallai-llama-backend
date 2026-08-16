package com.dalai.llama.creator.service.screenplayvideo;

import com.dalai.llama.creator.domain.entity.CreatorScriptShotPlan;
import com.dalai.llama.creator.dto.shotplan.CameraPlanSheetTagView;
import com.dalai.llama.creator.dto.shotplan.CharacterRenderSpecView;
import com.dalai.llama.creator.dto.shotplan.CoverageSpecView;
import com.dalai.llama.creator.dto.shotplan.FloorPlanView;
import com.dalai.llama.creator.dto.shotplan.FramePreviewView;
import com.dalai.llama.creator.dto.shotplan.LightPlacementView;
import com.dalai.llama.creator.dto.shotplan.LightingBuildSheetTagView;
import com.dalai.llama.creator.dto.shotplan.MovementSpecView;
import com.dalai.llama.creator.dto.shotplan.PerspectiveView;
import com.dalai.llama.creator.dto.shotplan.ShotPlanTagMapper;
import com.dalai.llama.creator.dto.shotplan.StoryboardTagView;
import com.dalai.llama.creator.repository.CreatorScriptShotPlanRepository;
import com.dalai.llama.creator.service.ProductionPlanTagService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import static com.dalai.llama.creator.service.screenplayvideo.MapCoercion.firstValue;
import static com.dalai.llama.creator.service.screenplayvideo.MapCoercion.intValue;
import static com.dalai.llama.creator.service.screenplayvideo.MapCoercion.mapValue;
import static com.dalai.llama.creator.service.screenplayvideo.MapCoercion.stringValue;

@Component
public class ShotPlanTagGatewayImpl implements ShotPlanTagGateway {

    private final CreatorScriptShotPlanRepository shotPlanRepository;
    private final ObjectMapper objectMapper;

    public ShotPlanTagGatewayImpl(CreatorScriptShotPlanRepository shotPlanRepository, ObjectMapper objectMapper) {
        this.shotPlanRepository = shotPlanRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public void attachTo(CreatorScriptShotPlan plan, Map<String, Object> scene) {
        if (plan == null || scene == null) {
            return;
        }
        Map<String, Object> storyboardTagMap = mapValue(plan.getStoryboardTag());
        Map<String, Object> lightingBuildSheetTagMap = mapValue(plan.getLightingBuildSheetTag());
        Map<String, Object> cameraPlanSheetTagMap = mapValue(plan.getCameraPlanSheetTag());
        if (!storyboardTagMap.isEmpty()) {
            scene.put("storyboardTag", storyboardTagMap);
        }
        if (!lightingBuildSheetTagMap.isEmpty()) {
            scene.put("lightingBuildSheetTag", lightingBuildSheetTagMap);
        }
        if (!cameraPlanSheetTagMap.isEmpty()) {
            scene.put("cameraPlanSheetTag", cameraPlanSheetTagMap);
        }

        StoryboardTagView storyboardTag = ShotPlanTagMapper.storyboardTag(storyboardTagMap, objectMapper);
        LightingBuildSheetTagView lightingTag = ShotPlanTagMapper.lightingBuildSheetTag(lightingBuildSheetTagMap, objectMapper);
        CameraPlanSheetTagView cameraTag = ShotPlanTagMapper.cameraPlanSheetTag(cameraPlanSheetTagMap, objectMapper);

        if (!storyboardTag.productShotType().isBlank()) {
            scene.put("productShotType", storyboardTag.productShotType());
        }

        // Compress, don't drop: lightingBuildSheetTag/cameraPlanSheetTag carry a lot of
        // physical-production detail (gear brand names, rig distances in feet, setup minutes,
        // safety-coordinator flags) written for a human crew building a physical set - that has
        // no video-model translation and is deliberately left out. But the DESCRIPTIVE substance
        // of the same plan - what the light/camera setup is actually meant to look like - is
        // pulled in from every tag, not just storyboardTag, so nothing visually meaningful is lost.
        List<String> lightingParts = new ArrayList<>();
        addLabeledPart(lightingParts, "Key light", storyboardTag.keyLightSourceLabel());
        addLabeledPart(lightingParts, "Atmosphere", storyboardTag.lightingAtmosphericDescription());
        addLabeledPart(lightingParts, "Intent", lightingTag.cinematicIntent());
        addLabeledPart(lightingParts, "Lighting layout", nullSafe(lightingTag.perspectiveView(), PerspectiveView::narrativeDescription));
        FloorPlanView floorPlan = lightingTag.floorPlan();
        // Map.entry() rejects null values (most shots only light some of the four instruments) -
        // AbstractMap.SimpleEntry allows it.
        List<AbstractMap.SimpleEntry<String, LightPlacementView>> lightPlacements = List.of(
                new AbstractMap.SimpleEntry<>("keyLight", floorPlan.keyLight()),
                new AbstractMap.SimpleEntry<>("fillLight", floorPlan.fillLight()),
                new AbstractMap.SimpleEntry<>("rimLight", floorPlan.rimLight()),
                new AbstractMap.SimpleEntry<>("negFill", floorPlan.negFill())
        );
        for (Map.Entry<String, LightPlacementView> entry : lightPlacements) {
            LightPlacementView light = entry.getValue();
            if (light == null) {
                continue;
            }
            String position = light.position();
            String modifier = light.modifier();
            if (position.isBlank() && modifier.isBlank()) {
                continue;
            }
            String detail = position.isBlank() || modifier.isBlank() ? position + modifier : position + ", " + modifier;
            lightingParts.add((light.role().isBlank() ? entry.getKey() : light.role()) + ": " + detail);
        }
        if (!lightingParts.isEmpty()) {
            scene.put("lighting", String.join(". ", lightingParts));
        }

        List<String> cameraParts = new ArrayList<>();
        addLabeledPart(cameraParts, "Angle", storyboardTag.cameraAngle());
        addLabeledPart(cameraParts, "Movement", storyboardTag.cameraMovement());
        addLabeledPart(cameraParts, "Lens", storyboardTag.lensSuggestion());
        addLabeledPart(cameraParts, "Composition", storyboardTag.compositionSummary());
        addLabeledPart(cameraParts, "Headroom", storyboardTag.headroomNote());
        addLabeledPart(cameraParts, "Frame left", storyboardTag.frameLeftNote());
        addLabeledPart(cameraParts, "Frame right", storyboardTag.frameRightNote());
        addLabeledPart(cameraParts, "Focal point", storyboardTag.targetFocalPoint());
        addLabeledPart(cameraParts, "Move type", nullSafe(cameraTag.movementSpec(), MovementSpecView::moveType));
        addLabeledPart(cameraParts, "Move speed", nullSafe(cameraTag.movementSpec(), MovementSpecView::speed));
        addLabeledPart(cameraParts, "Subject placement", nullSafe(cameraTag.framePreview(), FramePreviewView::subjectPlacement));
        addLabeledPart(cameraParts, "Caption position", nullSafe(cameraTag.framePreview(), FramePreviewView::captionPosition));
        addLabeledPart(cameraParts, "Editor intent", nullSafe(cameraTag.coverageSpec(), CoverageSpecView::editorIntent));
        if (!cameraParts.isEmpty()) {
            scene.put("camera", String.join(" | ", cameraParts));
        }

        // Emotional arc: no existing scene field carries this, so it's new - sceneDetailPacket()
        // picks it up via its own emotionalDirection line.
        List<String> emotionParts = new ArrayList<>();
        addLabeledPart(emotionParts, "Expression", storyboardTag.expression());
        addLabeledPart(emotionParts, "Emotion", storyboardTag.emotion());
        addLabeledPart(emotionParts, "Intensity", storyboardTag.emotionIntensityOrZero());
        addLabeledPart(emotionParts, "Body language", storyboardTag.bodyLanguage());
        if (!emotionParts.isEmpty()) {
            scene.put("emotionalDirection", String.join(". ", emotionParts));
        }

        // Beat, editing/transition direction, and sound design all already have an existing
        // scene field they flow through (narrativeBeat feeds the existing hook/productStoryBeat
        // fallback chain in ProviderRequestBuilder; creatorDirection and audioDescription are
        // read directly elsewhere) - append onto whatever the screenplay already wrote there
        // instead of overwriting it, so nothing upstream is lost.
        appendPart(scene, "narrativeBeat", storyboardTag.narrativeBeatSummary());
        appendPart(scene, "backgroundDetail", storyboardTag.setDesign());
        appendPart(scene, "backgroundDetail", storyboardTag.environment());
        appendPart(scene, "backgroundDetail", storyboardTag.sceneTimeOfDay());
        appendPart(scene, "backgroundDetail", String.join(", ", storyboardTag.culturalReferences()));
        appendPart(scene, "creatorDirection", storyboardTag.transitionNote());
        appendPart(scene, "creatorDirection", storyboardTag.directorNote());
        appendPart(scene, "creatorDirection", storyboardTag.creatorTip());
        appendPart(scene, "creatorDirection", cameraTag.directorNote());
        appendPart(scene, "audioDescription", storyboardTag.ambientBedDescription());
        appendPart(scene, "audioDescription", storyboardTag.syncHitDescription());

        List<CharacterRenderSpecView> characters = new ArrayList<>();
        characters.addAll(storyboardTag.primaryCharacters());
        characters.addAll(storyboardTag.sideCharacters());
        if (!characters.isEmpty()) {
            List<Map<String, Object>> summarized = new ArrayList<>();
            for (CharacterRenderSpecView character : characters) {
                Map<String, Object> summary = new LinkedHashMap<>();
                summary.put("name", character.storyCharacterName());
                summary.put("archetype", stringValue(character.archetypeLabel(), ""));
                summary.put("distinguishingFeatures", character.distinguishingFeatures());
                summary.put("wardrobeThisShot", character.wardrobeThisShot());
                summarized.add(summary);
            }
            scene.put("characterDetail", summarized);
        }
    }

    /**
     * Without this, ANY chat-requested change is silently lost the moment the user clicks
     * generate again: applySceneEdit() only merges into the run's cached scene map, but attachTo()
     * above - called on every generate/regenerate - unconditionally overwrites storyboardTag (and
     * everything derived from it: camera, lighting, emotionalDirection, characterDetail/
     * wardrobeThisShot, narrativeBeat, backgroundDetail, creatorDirection, audioDescription)
     * straight from CreatorScriptShotPlan.storyboardTag in the DB, wiping out whatever the chat
     * edit just changed on that same tag. The AI edit prompt (SceneChatEditor.buildSceneEditPrompt)
     * is instructed to return every untouched field exactly as it was, so editedScene's
     * storyboardTag is already the full correct next state, not just a delta - persist it wholesale
     * rather than re-deriving individual keys, so nothing the chat touched (wardrobe, camera,
     * lighting, expression, ...) gets reverted on the next generate. Mirrors the write-back half of
     * StoryboardService.editShotWithAi()'s pattern.
     */
    @Override
    public void persistEditedTag(UUID scriptId, Map<String, Object> editedScene, int sceneIndex) {
        if (scriptId == null || editedScene == null) {
            return;
        }
        int shotNumber = intValue(firstValue(editedScene.get("shotNumber"), editedScene.get("sceneNumber")), sceneIndex + 1);
        if (shotNumber <= 0) {
            return;
        }
        CreatorScriptShotPlan plan = shotPlanRepository
                .findByScriptIdAndShotNumberAndStyleKey(scriptId, shotNumber, ProductionPlanTagService.DEFAULT_STYLE_KEY)
                .orElse(null);
        if (plan == null) {
            return;
        }
        Map<String, Object> editedTag = mapValue(editedScene.get("storyboardTag"));
        Map<String, Object> updatedTag;
        if (!editedTag.isEmpty()) {
            // The AI edit already returned the full next-state tag (untouched fields preserved
            // verbatim per the edit prompt's rules) - persist it as-is instead of re-deriving.
            updatedTag = new LinkedHashMap<>(editedTag);
        } else {
            // Fallback for scenes with no structured storyboardTag on the edit result: patch just
            // the tone fields the edit result carries directly, as before.
            updatedTag = new LinkedHashMap<>(mapValue(plan.getStoryboardTag()));
            boolean changed = false;
            for (String key : List.of("expression", "emotion", "emotionIntensity", "bodyLanguage")) {
                String value = stringValue(editedScene.get(key), "");
                if (!value.isBlank()) {
                    updatedTag.put(key, editedScene.get(key));
                    changed = true;
                }
            }
            if (!changed) {
                return;
            }
        }
        // storyboardTag uses ReplacementOnlyJsonMutabilityPlan - a brand-new Map instance is
        // required for Hibernate to detect and persist the change, an in-place mutation would be
        // silently ignored.
        plan.setStoryboardTag(updatedTag);
        shotPlanRepository.save(plan);
    }

    private static <T, R> String nullSafe(T value, Function<T, R> accessor) {
        if (value == null) {
            return "";
        }
        R result = accessor.apply(value);
        return result == null ? "" : String.valueOf(result);
    }

    private void addLabeledPart(List<String> parts, String label, Object value) {
        String text = stringValue(value, "");
        if (!text.isBlank()) {
            parts.add(label + ": " + text);
        }
    }

    /** Combines onto whatever is already at scene.get(key) instead of overwriting it. */
    private void appendPart(Map<String, Object> scene, String key, String addition) {
        if (addition == null || addition.isBlank()) {
            return;
        }
        String existing = stringValue(scene.get(key), "");
        scene.put(key, existing.isBlank() ? addition : existing + ". " + addition);
    }
}
