package com.dalai.llama.creator.dto.shotplan;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Typed read view of CreatorScriptShotPlan.cameraPlanSheetTag - mirrors
 * ProductionPlanTagService.cameraPlanSheetTagSchemaReference() exactly. See
 * {@link StoryboardTagView} for the read-view-not-storage-model contract this follows.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CameraPlanSheetTagView(
        @JsonProperty("projectTitle") String projectTitle,
        @JsonProperty("shotNumber") Integer shotNumber,
        @JsonProperty("shotTitle") String shotTitle,
        @JsonProperty("startTimeSeconds") Double startTimeSeconds,
        @JsonProperty("endTimeSeconds") Double endTimeSeconds,
        @JsonProperty("durationSeconds") Double durationSeconds,
        @JsonProperty("maxClipSeconds") Integer maxClipSeconds,
        @JsonProperty("maxDialogueSecondsPerShot") Integer maxDialogueSecondsPerShot,
        @JsonProperty("dialogueTimingPolicy") String dialogueTimingPolicy,
        @JsonProperty("fps") Integer fps,
        /** Camera framing size (ECU/CU/MCU/MS/WS) - same field/meaning as StoryboardTagView.shotType. */
        @JsonProperty("shotType") String shotType,
        @JsonProperty("cameraAngle") String cameraAngle,
        @JsonProperty("cameraMovement") String cameraMovement,
        @JsonProperty("lensSuggestion") String lensSuggestion,
        @JsonProperty("coverageType") String coverageType,
        @JsonProperty("screenType") String screenType,
        @JsonProperty("screenDirection") String screenDirection,
        @JsonProperty("directorInitials") String directorInitials,
        @JsonProperty("blockingMap") BlockingMapView blockingMap,
        @JsonProperty("framePreview") FramePreviewView framePreview,
        @JsonProperty("cameraRig") CameraRigView cameraRig,
        @JsonProperty("movementSpec") MovementSpecView movementSpec,
        @JsonProperty("gimbalSettings") GimbalSettingsView gimbalSettings,
        @JsonProperty("coverageSpec") CoverageSpecView coverageSpec,
        @JsonProperty("executionSteps") List<ExecutionStepView> executionSteps,
        @JsonProperty("safetyFlags") List<String> safetyFlags,
        @JsonProperty("requiresCoordinator") Boolean requiresCoordinator,
        @JsonProperty("complianceNote") String complianceNote,
        @JsonProperty("directorNote") String directorNote,
        @JsonProperty("imageGenerationPromptOverride") String imageGenerationPromptOverride
) {
    public String cameraAngle() {
        return cameraAngle == null ? "" : cameraAngle;
    }

    public String cameraMovement() {
        return cameraMovement == null ? "" : cameraMovement;
    }

    public String directorNote() {
        return directorNote == null ? "" : directorNote;
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
record BlockingMapView(
        @JsonProperty("actors") List<ActorBlockingView> actors,
        @JsonProperty("cameraStartEnd") CameraStartEndView cameraStartEnd,
        @JsonProperty("keyProps") List<KeyPropView> keyProps,
        @JsonProperty("oneEightyLineNote") String oneEightyLineNote,
        @JsonProperty("roomDimensions") String roomDimensions
) {
}

@JsonIgnoreProperties(ignoreUnknown = true)
record ActorBlockingView(
        @JsonProperty("characterName") String characterName,
        @JsonProperty("age") Integer age,
        @JsonProperty("heightImpression") String heightImpression,
        @JsonProperty("startPosition") String startPosition,
        @JsonProperty("endPosition") String endPosition,
        @JsonProperty("movementPath") String movementPath,
        @JsonProperty("movementDistanceFeet") Double movementDistanceFeet
) {
}

@JsonIgnoreProperties(ignoreUnknown = true)
record CameraStartEndView(
        @JsonProperty("startPosition") String startPosition,
        @JsonProperty("endPosition") String endPosition,
        @JsonProperty("movementDescription") String movementDescription,
        @JsonProperty("startDistanceFeet") Double startDistanceFeet,
        @JsonProperty("endDistanceFeet") Double endDistanceFeet
) {
}

@JsonIgnoreProperties(ignoreUnknown = true)
record KeyPropView(
        @JsonProperty("propName") String propName,
        @JsonProperty("placementNote") String placementNote,
        @JsonProperty("handOrSide") String handOrSide
) {
}

@JsonIgnoreProperties(ignoreUnknown = true)
record CameraRigView(
        @JsonProperty("cameraBody") String cameraBody,
        @JsonProperty("lensSuggestion") String lensSuggestion,
        @JsonProperty("fps") Integer fps,
        @JsonProperty("shutterAngle") String shutterAngle,
        @JsonProperty("iso") String iso,
        @JsonProperty("aperture") String aperture,
        @JsonProperty("whiteBalance") String whiteBalance,
        @JsonProperty("filter") String filter
) {
}

@JsonIgnoreProperties(ignoreUnknown = true)
record GimbalSettingsView(
        @JsonProperty("enabled") Boolean enabled,
        @JsonProperty("device") String device,
        @JsonProperty("mode") String mode,
        @JsonProperty("axisLock") String axisLock,
        @JsonProperty("panSpeed") Double panSpeed,
        @JsonProperty("tiltSpeed") Double tiltSpeed,
        @JsonProperty("deadband") Double deadband,
        @JsonProperty("followDurationSeconds") Double followDurationSeconds,
        @JsonProperty("horizonLock") Boolean horizonLock,
        @JsonProperty("stabilizationStrength") String stabilizationStrength,
        @JsonProperty("operatorPath") String operatorPath,
        @JsonProperty("rehearsalCue") String rehearsalCue
) {
}

@JsonIgnoreProperties(ignoreUnknown = true)
record ExecutionStepView(
        @JsonProperty("stepNumber") Integer stepNumber,
        @JsonProperty("title") String title,
        @JsonProperty("instruction") String instruction
) {
}
