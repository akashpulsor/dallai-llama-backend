package com.dalai.llama.creator.dto.screenplay;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * Typed, read-only view of a screenplay video run's {@code scene} JSONB blob - the same pattern
 * as {@link com.dalai.llama.creator.dto.shotplan.StoryboardTagView}, applied to
 * ScreenplayVideoService's own core data shape instead of the shot-plan tags. This is a READ VIEW,
 * not the persistence model: ScreenplayVideoService and its extracted collaborators
 * (FounderSceneAudioCloner, InitialRunAssembler, DialogueVoiceCloner, VideoRunHydrator,
 * CastCharacterResolver, VideoGenerationBiller, ReferenceImageResolver, FinalVideoRenderer) all
 * keep reading and writing {@code Map<String, Object> scene} exactly as before; parse into this
 * view with {@link SceneViewMapper#sceneView} only where you want typed, autocomplete-friendly
 * access instead of scattered {@code firstText(scene.get("x"), scene.get("x_alt"))} guessing.
 * Never write this view back to storage in place of the original map.
 *
 * <p>Fields were census'd from every {@code scene.get("...")}/{@code scene.put("...")} literal
 * across ScreenplayVideoService.java and its 8 extracted collaborators. A handful of concepts have
 * more than one spelling in the wild (different AI-provider integrations and code eras never got
 * consolidated) - {@code @JsonAlias} collapses each onto one canonical accessor, but ONLY where an
 * existing {@code firstText(scene.get("x"), scene.get("x_alt"))}-shaped fallback in the actual code
 * already proves the two spellings are read as the same concept (each aliased field below cites
 * where). Field-name similarity alone is not treated as evidence - most duplicate-looking names in
 * this file are kept as separate, un-aliased fields on purpose, because nothing yet proves they're
 * interchangeable and this view must never silently merge two fields that turn out to differ.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScreenplaySceneView(
        // --- identity ---
        // id/sceneId/scene_id: InitialRunAssembler.buildInitialRun writes the same value to both
        // "id" and "sceneId" (scene.put("id", sceneId); scene.put("sceneId", sceneId)), and
        // ScreenplayVideoService.findSceneIndex already reads them as one fallback chain
        // (firstText(scene.get("id"), scene.get("sceneId"), scene.get("scene_id"))) - merging here
        // codifies behavior the existing code already relies on, not a new assumption.
        @JsonAlias({"sceneId", "scene_id"}) String id,
        Integer shotNumber,
        Integer sceneNumber,
        // Verified: InitialRunAssembler.buildInitialRun reads
        // firstText(source.get("title"), source.get("beatTitle"), source.get("narrativeBeat"), ...).
        @JsonAlias("beatTitle") String title,
        String status,
        String provider,
        String targetProvider,
        String model,
        String brollProvider,
        String brollModel,
        String generationMode,

        // --- timing ---
        Integer startSeconds,
        Integer endSeconds,
        String startTime,
        String endTime,
        // Verified: InitialRunAssembler.buildInitialRun reads
        // firstValue(source.get("durationSeconds"), source.get("duration_seconds"), source.get("duration")).
        @JsonAlias({"duration_seconds", "duration"}) Integer durationSeconds,
        Integer maxClipSeconds,

        // --- prompt / creative content ---
        String prompt,
        String providerPrompt,
        String action,
        String visual,
        String description,
        String purpose,
        String visualPrompt,
        String visualSummary,
        String seedancePrompt,
        String storyboardImagePrompt,
        String imagePrompt,
        String animationPrompt,
        String videoPrompt,
        // Verified: ProviderRequestBuilder.resolvePromptAndSceneDetail reads
        // firstMap(..., scene.get("videoDirectorPlan"), scene.get("video_director_plan"),
        // scene.get("directorPlan"), scene.get("director_plan")).
        @JsonAlias({"video_director_plan", "directorPlan", "director_plan"}) Map<String, Object> videoDirectorPlan,
        String videoMotionPrompt,
        String narrativeBeat,
        // Verified: ScreenplayVideoService.productCgiMotionPrompt (InitialRunAssembler) reads
        // firstText(scene.get("retentionGoal"), scene.get("retention_goal")).
        @JsonAlias("retention_goal") String retentionGoal,
        // Verified: same method, firstText(scene.get("patternInterrupt"), scene.get("pattern_interrupt")).
        @JsonAlias("pattern_interrupt") String patternInterrupt,
        String hook,
        String hookLine,
        String openingHook,
        // Verified: same method, firstText(scene.get("cameraMovement"), scene.get("cameraMove"), scene.get("motion"), ...).
        @JsonAlias({"cameraMove", "motion"}) String cameraMovement,
        String cameraAngle,
        String lens,
        String lensSuggestion,
        String lighting,
        String lightingSetup,
        String environment,
        String setDesign,
        String background,
        String continuityNotes,
        String transitionReason,
        // Verified: InitialRunAssembler.enrichProductCgiScenePlan reads
        // firstText(scene.get("negativePrompt"), scene.get("negative_prompt")).
        @JsonAlias("negative_prompt") String negativePrompt,
        String bRollOpportunity,
        String brollStyle,

        // --- text overlay / caption ---
        String textOverlay,
        String caption,
        String captionText,
        Map<String, Object> captionStyle,
        String captionSafeArea,

        // --- dialogue / voice-clone ---
        String dialogue,
        String dialogueScript,
        String character,
        String characterDetail,
        String characterDetails,
        String characters,
        String sceneDetail,
        String sourceDialogueLanguage,
        String dialogueLanguage,
        String languageCode,
        Boolean dialogueTranslationApplied,
        String dialogueLocalizationStatus,
        java.util.UUID dialogueLocalizationPromptRunId,
        Boolean dialogueCoverageRequired,
        Map<String, Object> dialogueAudio,
        String voiceTrack,
        // Verified: DialogueVoiceCloner.generateSceneDialogueVoice writes the same value to both
        // scene.put("dialogueCloneVoiceModel", selectedVoiceMethod) and
        // scene.put("dialogueCloneMethod", selectedVoiceMethod) - always kept in sync, not
        // independent fields.
        @JsonAlias("dialogueCloneVoiceModel") String dialogueCloneMethod,
        String dialogueCloneStatus,
        Boolean dialogueCloneAccepted,
        String dialogueCloneAcceptedAt,
        String dialogueCloneAcceptedBy,
        String dialogueCloneError,
        String dialogueCloneFingerprint,
        String dialogueCloneGeneratedAt,
        String dialogueCloneText,
        String dialogueCloneLanguage,
        String dialogueCloneLanguageCode,
        String activeSpeaker,

        // --- product / no-human CGI ---
        Boolean noHumans,
        Boolean productLed,
        Boolean productCgiScene,
        String productShotType,
        // Verified: InitialRunAssembler.enrichProductCgiScenePlan reads
        // firstMap(scene.get("productShotPlan"), scene.get("product_shot_plan")).
        @JsonAlias("product_shot_plan") Map<String, Object> productShotPlan,
        String productStoryBeat,
        Map<String, Object> productCreativeEvidence,
        String productImagePrompt,
        String productionImagePrompt,
        String productMotionPrompt,
        String nextProductImagePrompt,
        String generatedProductImageUrl,
        List<Map<String, Object>> productImageAssets,
        List<Map<String, Object>> generatedProductImageAssets,
        String productionImage,
        Map<String, Object> productImageAsset,

        // --- reference / provider request ---
        String referenceImageDetails,
        List<Map<String, Object>> referenceImageAssets,
        List<Map<String, Object>> referenceAssets,
        Map<String, Object> providerRequest,
        Map<String, Object> ragContext,
        Map<String, Object> storyboardTag,
        Map<String, Object> founderAvatarProfile,
        String avatarProviderMode,
        String avatarSceneSelectionReason,
        Map<String, Object> avatarPortraitAsset,
        String avatarPortraitUrl,
        Map<String, Object> videoAsset,
        String videoUrl,
        String clipUrl,
        String bucket,
        String objectKey,

        // --- scoring / evidence (client-review, transcript) ---
        Double confidence,
        String evidenceMode,
        String source,
        String label,
        Integer index,
        List<String> frameIds,
        String representativeTimestamp,
        String summary,

        String updatedAt
) {
    public String id() {
        return id == null ? "" : id;
    }

    public String title() {
        return title == null ? "" : title;
    }

    public String status() {
        return status == null ? "" : status;
    }

    public String action() {
        return action == null ? "" : action;
    }

    public String cameraMovement() {
        return cameraMovement == null ? "" : cameraMovement;
    }

    public String dialogueScript() {
        return dialogueScript == null ? "" : dialogueScript;
    }

    public String dialogueCloneStatus() {
        return dialogueCloneStatus == null ? "" : dialogueCloneStatus;
    }

    public boolean dialogueCloneAcceptedOrFalse() {
        return Boolean.TRUE.equals(dialogueCloneAccepted);
    }

    public String dialogueLanguage() {
        return dialogueLanguage == null ? "" : dialogueLanguage;
    }

    public boolean noHumansOrFalse() {
        return Boolean.TRUE.equals(noHumans);
    }

    public boolean productCgiSceneOrFalse() {
        return Boolean.TRUE.equals(productCgiScene);
    }

    public String productShotType() {
        return productShotType == null ? "" : productShotType;
    }

    public Map<String, Object> providerRequest() {
        return providerRequest == null ? Map.of() : providerRequest;
    }

    public Map<String, Object> storyboardTag() {
        return storyboardTag == null ? Map.of() : storyboardTag;
    }

    public Map<String, Object> founderAvatarProfile() {
        return founderAvatarProfile == null ? Map.of() : founderAvatarProfile;
    }

    public String negativePrompt() {
        return negativePrompt == null ? "" : negativePrompt;
    }

    public String environment() {
        return environment == null ? "" : environment;
    }

    public String lighting() {
        return lighting == null ? "" : lighting;
    }
}
