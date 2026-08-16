package com.dalai.llama.creator.dto.shotplan;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Typed read view of CreatorScriptShotPlan.lightingBuildSheetTag - mirrors
 * ProductionPlanTagService.lightingBuildSheetTagSchemaReference() exactly. See
 * {@link StoryboardTagView} for the read-view-not-storage-model contract this follows.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LightingBuildSheetTagView(
        @JsonProperty("projectTitle") String projectTitle,
        @JsonProperty("shotTitle") String shotTitle,
        @JsonProperty("shotNumber") Integer shotNumber,
        @JsonProperty("cinematicIntent") String cinematicIntent,
        @JsonProperty("budgetTier") String budgetTier,
        @JsonProperty("estimatedSetupMinutes") Integer estimatedSetupMinutes,
        @JsonProperty("directorInitials") String directorInitials,
        @JsonProperty("maxClipSeconds") Integer maxClipSeconds,
        @JsonProperty("maxDialogueSecondsPerShot") Integer maxDialogueSecondsPerShot,
        @JsonProperty("dialogueTimingPolicy") String dialogueTimingPolicy,
        @JsonProperty("floorPlan") FloorPlanView floorPlan,
        @JsonProperty("perspectiveView") PerspectiveView perspectiveView,
        @JsonProperty("gearCards") List<GearCardView> gearCards,
        @JsonProperty("buildSteps") List<BuildStepView> buildSteps,
        @JsonProperty("imageGenerationPromptOverride") String imageGenerationPromptOverride
) {
    public String cinematicIntent() {
        return cinematicIntent == null ? "" : cinematicIntent;
    }

    public FloorPlanView floorPlan() {
        return floorPlan == null ? new FloorPlanView(null, null, null, null, null, null, null, null) : floorPlan;
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
record GearCardView(
        @JsonProperty("cardNumber") Integer cardNumber,
        @JsonProperty("roleLabel") String roleLabel,
        @JsonProperty("itemName") String itemName,
        @JsonProperty("setupBullets") List<String> setupBullets
) {
}

@JsonIgnoreProperties(ignoreUnknown = true)
record BuildStepView(
        @JsonProperty("stepNumber") Integer stepNumber,
        @JsonProperty("title") String title,
        @JsonProperty("instruction") String instruction,
        @JsonProperty("estimatedMinutes") Integer estimatedMinutes
) {
}
