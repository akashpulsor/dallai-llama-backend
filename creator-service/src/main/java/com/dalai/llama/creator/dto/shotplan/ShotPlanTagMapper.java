package com.dalai.llama.creator.dto.shotplan;

import com.dalai.llama.creator.domain.entity.CreatorScriptShotPlan;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * Parses the existing Map&lt;String,Object&gt; JSONB tag blobs into typed, read-only views (see
 * {@link StoryboardTagView}). This does NOT replace persistence - CreatorScriptShotPlan still
 * stores and ProductionPlanTagService still saves the AI provider's raw map untouched; parsing
 * here is purely for type-safe, autocomplete-friendly READ access in new code (critics, future
 * orchestrator/UI wiring) instead of stringly-typed map.get("key") lookups.
 *
 * Never write a view object back into storage in place of the original map - any field a view
 * doesn't model would be silently dropped. Views are @JsonIgnoreProperties(ignoreUnknown = true)
 * specifically so a field the AI adds that isn't modeled here doesn't break parsing; it's just
 * invisible to the typed view (the original map, which is what's actually persisted, still has it).
 */
public final class ShotPlanTagMapper {

    private ShotPlanTagMapper() {
    }

    public static StoryboardTagView storyboardTag(Map<String, Object> raw, ObjectMapper objectMapper) {
        return convert(raw, StoryboardTagView.class, objectMapper, emptyStoryboardTag());
    }

    public static LightingBuildSheetTagView lightingBuildSheetTag(Map<String, Object> raw, ObjectMapper objectMapper) {
        return convert(raw, LightingBuildSheetTagView.class, objectMapper, emptyLightingTag());
    }

    public static CameraPlanSheetTagView cameraPlanSheetTag(Map<String, Object> raw, ObjectMapper objectMapper) {
        return convert(raw, CameraPlanSheetTagView.class, objectMapper, emptyCameraTag());
    }

    /** Combines all three tags plus the shot number for a single CreatorScriptShotPlan row. */
    public static ShotPlanView shotPlanView(CreatorScriptShotPlan plan, ObjectMapper objectMapper) {
        if (plan == null) {
            return new ShotPlanView(0, emptyStoryboardTag(), emptyLightingTag(), emptyCameraTag());
        }
        return new ShotPlanView(
                plan.getShotNumber() == null ? 0 : plan.getShotNumber(),
                storyboardTag(plan.getStoryboardTag(), objectMapper),
                lightingBuildSheetTag(plan.getLightingBuildSheetTag(), objectMapper),
                cameraPlanSheetTag(plan.getCameraPlanSheetTag(), objectMapper)
        );
    }

    private static <T> T convert(Map<String, Object> raw, Class<T> type, ObjectMapper objectMapper, T fallback) {
        if (raw == null || raw.isEmpty()) {
            return fallback;
        }
        try {
            return objectMapper.convertValue(raw, type);
        } catch (IllegalArgumentException ex) {
            // A parse failure here means the stored/AI JSON has a genuine type mismatch against
            // the schema (not just an extra field, which ignoreUnknown already tolerates) -
            // fall back to an empty typed view rather than throw, since this is a read
            // convenience layer and must never be a new way for critic/UI code to break; the
            // original map (what's actually persisted and used everywhere else) is untouched.
            return fallback;
        }
    }

    private static StoryboardTagView emptyStoryboardTag() {
        return new StoryboardTagView(
                "", "", "", "", "", "", "",              // projectTitle..narrativeBeatSummary
                0.0, 0.0, 0.0,                            // startTimeSeconds..durationSeconds
                "", "", "",                                // shotType, shotTypeFullName, productShotType
                "", "", "",                                // cameraAngle, cameraMovement, lensSuggestion
                0,                                          // fps
                "", "", "",                                // coverageType, screenType, screenDirection
                "", "", "", "", "",                         // compositionSummary..targetFocalPoint
                java.util.List.of(), java.util.List.of(),  // primaryCharacters, sideCharacters
                0,                                          // peopleInFrame
                "", "", "",                                // setDesign, environment, sceneTimeOfDay
                java.util.List.of(),                        // culturalReferences
                "", "",                                     // lightingAtmosphericDescription, keyLightSourceLabel
                "", "", 0.0, "", "",                        // expression, emotion, emotionIntensity, bodyLanguage, action
                "",                                          // dialogueLanguage
                null,                                        // primaryDialogue
                "", "",                                      // textOverlay, textOverlayEmoji
                null,                                        // captionStyle
                "", "", "",                                  // ambientBedDescription, syncHitDescription, transitionNote
                0, "", "", "",                                // shootDay, shootBlock, budgetTier, formatTier
                "", "", ""                                    // directorNote, creatorTip, imageGenerationPromptOverride
        );
    }

    private static LightingBuildSheetTagView emptyLightingTag() {
        return new LightingBuildSheetTagView("", "", 0, "", "", 0, "", 0, 0, "", null, null, java.util.List.of(), java.util.List.of(), "");
    }

    private static CameraPlanSheetTagView emptyCameraTag() {
        return new CameraPlanSheetTagView(
                "", 0, "", 0.0, 0.0, 0.0, 0, 0, "", 0, "", "", "", "", "", "", "", "",
                null, null, null, null, null, null, java.util.List.of(), java.util.List.of(), false, "", "", ""
        );
    }
}
