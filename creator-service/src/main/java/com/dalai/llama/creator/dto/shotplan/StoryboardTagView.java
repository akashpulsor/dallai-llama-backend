package com.dalai.llama.creator.dto.shotplan;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Typed, read-only view of a {@code CreatorScriptShotPlan.storyboardTag} JSONB blob, for code
 * that needs to reason about the data (critics, the future orchestrator/UI wiring) instead of
 * doing stringly-typed {@code map.get("key")} lookups that silently return null on a typo and
 * give no IDE autocomplete or compile-time safety.
 *
 * This is a READ VIEW, not the persistence model - CreatorScriptShotPlan.storyboardTag stays a
 * Map<String,Object> (JSONB) exactly as it already is everywhere else in this codebase, and
 * ProductionPlanTagService still saves the AI provider's raw map untouched. Parse a map into this
 * view with {@link ShotPlanTagMapper#storyboardTag} whenever you want typed access; never write
 * this view back to storage instead of the original map, or any field this view doesn't model
 * (see {@code @JsonIgnoreProperties}) would be silently dropped on a round trip.
 *
 * Fields mirror ProductionPlanTagService.storyboardTagSchemaReference()/the STORYBOARD_TAG_GENERATE
 * prompt's output schema exactly (V33/V34/V66 migrations) - keep both in sync if either changes.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StoryboardTagView(
        @JsonProperty("projectTitle") String projectTitle,
        @JsonProperty("sequenceTitle") String sequenceTitle,
        @JsonProperty("sceneLocation") String sceneLocation,
        @JsonProperty("directorInitials") String directorInitials,
        @JsonProperty("shotTitle") String shotTitle,
        @JsonProperty("beatTitle") String beatTitle,
        @JsonProperty("narrativeBeatSummary") String narrativeBeatSummary,
        @JsonProperty("startTimeSeconds") Double startTimeSeconds,
        @JsonProperty("endTimeSeconds") Double endTimeSeconds,
        @JsonProperty("durationSeconds") Double durationSeconds,
        /** Camera framing size (ECU/CU/MCU/MS/WS) - NOT the marketing shot category, see productShotType. */
        @JsonProperty("shotType") String shotType,
        @JsonProperty("shotTypeFullName") String shotTypeFullName,
        /** Marketing/creative shot category (Hero Shot, Ingredient Shot, Pack Shot, ...). Blank when this script isn't product-led. Unrelated to shotType above. */
        @JsonProperty("productShotType") String productShotType,
        @JsonProperty("cameraAngle") String cameraAngle,
        @JsonProperty("cameraMovement") String cameraMovement,
        @JsonProperty("lensSuggestion") String lensSuggestion,
        @JsonProperty("fps") Integer fps,
        @JsonProperty("coverageType") String coverageType,
        @JsonProperty("screenType") String screenType,
        @JsonProperty("screenDirection") String screenDirection,
        @JsonProperty("compositionSummary") String compositionSummary,
        @JsonProperty("headroomNote") String headroomNote,
        @JsonProperty("frameLeftNote") String frameLeftNote,
        @JsonProperty("frameRightNote") String frameRightNote,
        @JsonProperty("targetFocalPoint") String targetFocalPoint,
        @JsonProperty("primaryCharacters") List<CharacterRenderSpecView> primaryCharacters,
        @JsonProperty("sideCharacters") List<CharacterRenderSpecView> sideCharacters,
        @JsonProperty("peopleInFrame") Integer peopleInFrame,
        @JsonProperty("setDesign") String setDesign,
        @JsonProperty("environment") String environment,
        @JsonProperty("sceneTimeOfDay") String sceneTimeOfDay,
        @JsonProperty("culturalReferences") List<String> culturalReferences,
        @JsonProperty("lightingAtmosphericDescription") String lightingAtmosphericDescription,
        @JsonProperty("keyLightSourceLabel") String keyLightSourceLabel,
        @JsonProperty("expression") String expression,
        @JsonProperty("emotion") String emotion,
        @JsonProperty("emotionIntensity") Double emotionIntensity,
        @JsonProperty("bodyLanguage") String bodyLanguage,
        @JsonProperty("action") String action,
        @JsonProperty("dialogueLanguage") String dialogueLanguage,
        @JsonProperty("primaryDialogue") DialogueLineView primaryDialogue,
        @JsonProperty("textOverlay") String textOverlay,
        @JsonProperty("textOverlayEmoji") String textOverlayEmoji,
        @JsonProperty("captionStyle") CaptionStyleView captionStyle,
        @JsonProperty("ambientBedDescription") String ambientBedDescription,
        @JsonProperty("syncHitDescription") String syncHitDescription,
        @JsonProperty("transitionNote") String transitionNote,
        @JsonProperty("shootDay") Integer shootDay,
        @JsonProperty("shootBlock") String shootBlock,
        @JsonProperty("budgetTier") String budgetTier,
        @JsonProperty("formatTier") String formatTier,
        @JsonProperty("directorNote") String directorNote,
        @JsonProperty("creatorTip") String creatorTip,
        @JsonProperty("imageGenerationPromptOverride") String imageGenerationPromptOverride
) {
    public String projectTitle() {
        return projectTitle == null ? "" : projectTitle;
    }

    public String productShotType() {
        return productShotType == null ? "" : productShotType;
    }

    public String lightingAtmosphericDescription() {
        return lightingAtmosphericDescription == null ? "" : lightingAtmosphericDescription;
    }

    public String keyLightSourceLabel() {
        return keyLightSourceLabel == null ? "" : keyLightSourceLabel;
    }

    public String setDesign() {
        return setDesign == null ? "" : setDesign;
    }

    public String environment() {
        return environment == null ? "" : environment;
    }

    public String narrativeBeatSummary() {
        return narrativeBeatSummary == null ? "" : narrativeBeatSummary;
    }

    public String beatTitle() {
        return beatTitle == null ? "" : beatTitle;
    }

    public String emotion() {
        return emotion == null ? "" : emotion;
    }

    public double emotionIntensityOrZero() {
        return emotionIntensity == null ? 0.0 : emotionIntensity;
    }

    public String action() {
        return action == null ? "" : action;
    }

    public String cameraAngle() {
        return cameraAngle == null ? "" : cameraAngle;
    }

    public String cameraMovement() {
        return cameraMovement == null ? "" : cameraMovement;
    }

    public String lensSuggestion() {
        return lensSuggestion == null ? "" : lensSuggestion;
    }

    public String compositionSummary() {
        return compositionSummary == null ? "" : compositionSummary;
    }

    public String headroomNote() {
        return headroomNote == null ? "" : headroomNote;
    }

    public String frameLeftNote() {
        return frameLeftNote == null ? "" : frameLeftNote;
    }

    public String frameRightNote() {
        return frameRightNote == null ? "" : frameRightNote;
    }

    public String targetFocalPoint() {
        return targetFocalPoint == null ? "" : targetFocalPoint;
    }

    public String expression() {
        return expression == null ? "" : expression;
    }

    public String bodyLanguage() {
        return bodyLanguage == null ? "" : bodyLanguage;
    }

    public String sceneTimeOfDay() {
        return sceneTimeOfDay == null ? "" : sceneTimeOfDay;
    }

    public List<String> culturalReferences() {
        return culturalReferences == null ? List.of() : culturalReferences;
    }

    public String transitionNote() {
        return transitionNote == null ? "" : transitionNote;
    }

    public String directorNote() {
        return directorNote == null ? "" : directorNote;
    }

    public String creatorTip() {
        return creatorTip == null ? "" : creatorTip;
    }

    public String ambientBedDescription() {
        return ambientBedDescription == null ? "" : ambientBedDescription;
    }

    public String syncHitDescription() {
        return syncHitDescription == null ? "" : syncHitDescription;
    }

    public List<CharacterRenderSpecView> primaryCharacters() {
        return primaryCharacters == null ? List.of() : primaryCharacters;
    }

    public List<CharacterRenderSpecView> sideCharacters() {
        return sideCharacters == null ? List.of() : sideCharacters;
    }
}
