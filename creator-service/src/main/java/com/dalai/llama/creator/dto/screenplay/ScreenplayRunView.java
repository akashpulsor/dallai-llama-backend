package com.dalai.llama.creator.dto.screenplay;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * Typed, read-only view of a screenplay video run's top-level JSONB envelope - the Run-level
 * counterpart to {@link ScreenplaySceneView}, same pattern, same rules: this is a READ VIEW, never
 * the persistence model. ScreenplayVideoService and its 8 extracted collaborators keep reading and
 * writing {@code Map<String, Object> run} exactly as before; parse into this view with
 * {@link RunViewMapper#runView} only where typed access is wanted. Never write this view back to
 * storage in place of the original map.
 *
 * <p>Census'd from every {@code run.get("...")}/{@code run.put("...")} literal across
 * ScreenplayVideoService.java and its 8 extracted collaborators (~123 distinct keys) - a
 * meaningfully cleaner data shape than Scene's: only 7 confirmed duplicate spellings found, each
 * proven by an actual duplicate-write in the real code (not a guess), cited per field below. Run
 * has far more distinct plan/asset sub-objects than Scene, most of which already have their own
 * narrower concerns elsewhere in the codebase (e.g. billing fields mirror
 * VideoGenerationBiller.refreshBillingSummary's output shape) - those stay modeled here as generic
 * {@code Map<String, Object>} rather than further nested view types, to keep this view's own scope
 * bounded to "what Run itself looks like."
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScreenplayRunView(
        // --- identity / envelope ---
        // Verified: InitialRunAssembler.buildInitialRun writes run.put("runId", runId.toString());
        // run.put("id", runId.toString()) - the same value under both keys.
        @JsonAlias("id") String runId,
        String jobId,
        String scriptId,
        String projectId,
        String tenantId,
        String userId,
        String title,
        String status,
        String provider,
        String model,
        List<Map<String, Object>> providerOptions,
        List<Map<String, Object>> modelOptions,
        Integer maxClipSeconds,
        Integer durationSeconds,
        Integer targetDurationSeconds,
        String screenType,
        String productionStyle,
        String hybridSceneMode,
        Boolean useSceneDialogue,
        Boolean noHumans,
        String shotPlanningMode,
        String brollStyle,
        String captionStyle,
        String generationWorkflow,
        Boolean prepareOnly,
        String rerunsFromRunId,

        // --- language / dialogue ---
        String sourceDialogueLanguage,
        String dialogueLanguage,
        String languageCode,
        Boolean dialogueLocalizationRequested,
        String dialogueLocalizationStatus,
        String dialogueScript,
        String voiceoverScript,
        Map<String, Object> dialogueVoiceProfile,
        String voiceProvider,

        // --- founder / avatar ---
        Boolean founderLedHybridEnabled,
        // Verified: InitialRunAssembler.buildInitialRun writes the same value to both
        // "founderAvatarProfile" and "founderKit".
        @JsonAlias("founderKit") Map<String, Object> founderAvatarProfile,
        // Verified: InitialRunAssembler.buildInitialRun writes run.put("avatarProvider",
        // run.get("avatarProviderMode")) immediately after setting avatarProviderMode.
        @JsonAlias("avatarProvider") String avatarProviderMode,
        String avatarId,
        String voiceId,
        String portraitEmbeddingId,
        String facialFeatureEmbeddingId,
        String voiceEmbeddingId,
        String avatarFallback,

        // --- product / reference images ---
        List<String> productImageUrls,
        List<Map<String, Object>> productImageAssets,
        List<String> referenceImageUrls,
        List<Map<String, Object>> referenceImageAssets,
        String referenceImageDetails,
        String referenceImageMode,
        String storyboardReferenceMode,
        Boolean requireImageAnchors,
        Map<String, Object> productUnderstanding,
        String category,
        String topicType,

        // --- cast ---
        List<Map<String, Object>> storyCharacters,
        List<Map<String, Object>> characterCastMappings,
        List<Map<String, Object>> availableActors,
        List<Map<String, Object>> characters,
        Map<String, Object> creatorContext,

        // --- planning ---
        Map<String, Object> videoPacingProfile,
        Map<String, Object> videoConsistencyBible,
        Map<String, Object> seedancePromptStrategy,
        Map<String, Object> videoFinishingPlan,
        Map<String, Object> soundDesignPlan,
        Map<String, Object> imageLedAdPlan,
        Map<String, Object> audioProductionPlan,
        Map<String, Object> dialoguePlan,
        Map<String, Object> musicPlan,
        Map<String, Object> freeMusicPlan,
        Map<String, Object> freeMusicSelectionPlan,
        // Verified: ScreenplayVideoService/InitialRunAssembler/VideoRunHydrator all write the same
        // value to "editingPlan" and "editorHandoffPlan" (run.put("editingPlan", editingPlan);
        // run.put("editorHandoffPlan", editingPlan)).
        @JsonAlias("editorHandoffPlan") Map<String, Object> editingPlan,
        List<String> recommendedEditingTools,
        Map<String, Object> audioMixStandards,
        Boolean burnCaptions,
        Boolean useMixedAudio,

        // --- SRT / script text ---
        String srt,
        Map<String, Object> srtFile,
        List<Map<String, Object>> srtCues,
        String screenplayJson,
        String scriptJson,

        // --- scenes ---
        // Verified: run.put("scenes", scenes); run.put("sceneClips", scenes) - the same list,
        // duplicate-written under two keys throughout ScreenplayVideoService and every extracted
        // collaborator that touches scenes.
        @JsonAlias("sceneClips") List<ScreenplaySceneView> scenes,
        Map<String, Object> timeline,
        Map<String, Object> renderManifest,
        Map<String, Object> lastGeneratedScene,
        Map<String, Object> lastSceneEdit,
        String lastRegeneratedSceneId,
        Map<String, Object> lastProviderRequest,
        Map<String, Object> lastProviderResponse,
        Boolean manualApprovalRequired,
        String manualApprovalSceneId,

        // --- audio assets ---
        Map<String, Object> dialogueAudio,
        String voiceTrack,
        Map<String, Object> backgroundMusic,
        String backgroundMusicPrompt,
        Map<String, Object> backgroundMusicSelection,
        String musicTrack,
        List<Map<String, Object>> audioAssets,
        Map<String, Object> audioPack,
        // Verified: DialogueVoiceCloner.decideSceneDialogueVoice / combineSceneDialogueAudio and
        // VideoRunHydrator.refreshAudioAssetUrls all write the same value to
        // "combinedDialogueAudio" and "combinedSceneDialogueAudio".
        @JsonAlias("combinedSceneDialogueAudio") Map<String, Object> combinedDialogueAudio,
        String combinedDialogueTrack,
        Map<String, Object> combinedDialogueSummary,
        Boolean audioStateRecoveredFromJob,

        // --- final video ---
        Map<String, Object> finalVideo,
        List<Map<String, Object>> finalVideoVariants,
        // Verified: ScreenplayVideoService.runFinalRenderJob and VideoRunHydrator.refreshRunMediaUrls
        // both write the same value to "videoUrl", "publicUrl", and "finalVideoUrl" in sequence.
        @JsonAlias({"publicUrl", "finalVideoUrl"}) String videoUrl,
        Map<String, Object> packageBilling,
        Map<String, Object> billingSummary,
        String billingMode,
        Boolean billingConsent,
        String lastBillingAction,

        String message,
        String createdAt,
        String updatedAt
) {
    public String runId() {
        return runId == null ? "" : runId;
    }

    public String scriptId() {
        return scriptId == null ? "" : scriptId;
    }

    public String status() {
        return status == null ? "" : status;
    }

    public String dialogueLanguage() {
        return dialogueLanguage == null ? "" : dialogueLanguage;
    }

    public List<ScreenplaySceneView> scenes() {
        return scenes == null ? List.of() : scenes;
    }

    public Map<String, Object> founderAvatarProfile() {
        return founderAvatarProfile == null ? Map.of() : founderAvatarProfile;
    }

    public String avatarProviderMode() {
        return avatarProviderMode == null ? "" : avatarProviderMode;
    }

    public Map<String, Object> editingPlan() {
        return editingPlan == null ? Map.of() : editingPlan;
    }

    public Map<String, Object> combinedDialogueAudio() {
        return combinedDialogueAudio == null ? Map.of() : combinedDialogueAudio;
    }

    public String videoUrl() {
        return videoUrl == null ? "" : videoUrl;
    }

    public boolean noHumansOrFalse() {
        return Boolean.TRUE.equals(noHumans);
    }

    public String message() {
        return message == null ? "" : message;
    }
}
