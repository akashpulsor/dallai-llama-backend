package com.dalai.llama.creator.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.dalai.llama.creator.service.screenplayvideo.MapCoercion.*;

/**
 * Builder pattern extraction of what used to be ScreenplayVideoService.buildProviderRequestForScene()
 * - a single 394-line procedure that assembled the provider-agnostic providerRequest map handed to
 * ScreenplayVideoProviderGenerationService. Same package as ScreenplayVideoService (not a separate
 * subpackage) deliberately: the ~15 domain-specific collaborator methods this needs
 * (castCharactersForScene, founderAvatarProfile, imageLedAdPlan, referenceImageUrlsForScene, ...)
 * are used throughout ScreenplayVideoService for other things too, so they stay there widened to
 * package-private rather than duplicated or made public API surface. The generic map/type-coercion
 * belt (firstText/firstMap/etc.) has its own extraction - {@link com.dalai.llama.creator.service.screenplayvideo.MapCoercion} -
 * statically imported here.
 *
 * <p>Each resolve*() step below is exactly one of the sections the original method built inline, in
 * the same order, so a diff against the original is section-by-section readable. One deliberate
 * behavioral improvement over the original (see resolveReferenceImages()): the provider-endpoint
 * if/else-if chain at the end is now a small lookup table (ProviderEndpoint record) instead of a
 * branch chain - a genuine Strategy-shaped piece, since exactly one endpoint config applies per
 * provider string. The product-frame/cast-face reference resolution deliberately is NOT modeled as
 * Strategy despite looking similar - both sources can and must combine (that mutual-exclusivity bug
 * is exactly what was fixed earlier this session) - so it's a straight-line "gather what's
 * available" section instead of a branch/strategy selection.
 */
final class ProviderRequestBuilder {

    private final ScreenplayVideoService owner;
    private final String provider;
    private final String model;
    private final Map<String, Object> scene;
    private final Map<String, Object> request;
    private final Map<String, Object> contextPayload;
    private final Map<String, Object> providerRequest = new LinkedHashMap<>();

    // Shared across resolve*() steps.
    private Map<String, Object> videoDirectorPlan;
    private List<Map<String, Object>> castCharactersForScene;
    private Map<String, Object> founderProfile;
    private Map<String, Object> videoFinishingPlan;
    private Map<String, Object> soundDesignPlan;
    private Map<String, Object> imageLedAdPlan;
    private Map<String, Object> audioProductionPlan;
    private Map<String, Object> editingPlan;
    private Map<String, Object> consistencyBible;
    private long seriesSeed;
    private long sceneSeed;
    private boolean noHumans;

    ProviderRequestBuilder(
            ScreenplayVideoService owner,
            String provider,
            String model,
            Map<String, Object> scene,
            Map<String, Object> request,
            Map<String, Object> contextPayload
    ) {
        this.owner = owner;
        this.provider = provider;
        this.model = model;
        this.scene = scene;
        this.request = request;
        this.contextPayload = contextPayload;
    }

    Map<String, Object> build() {
        providerRequest.put("provider", provider);
        providerRequest.put("model", model);
        resolvePromptAndSceneDetail();
        resolveCastAndReferencePolicy();
        resolveFounderAvatarIdentity();
        resolveFinishingPlans();
        resolveAudioFields();
        resolveReferenceImageModePolicy();
        resolveDialogueAndConsistency();
        resolveSeedAndNegativePrompt();
        resolveReferenceImages();
        resolveConsistencyStrategyAndProviderEndpoint();
        return providerRequest;
    }

    private void resolvePromptAndSceneDetail() {
        videoDirectorPlan = firstMap(
                request.get("videoDirectorPlan"),
                request.get("video_director_plan"),
                scene.get("videoDirectorPlan"),
                scene.get("video_director_plan"),
                scene.get("directorPlan"),
                scene.get("director_plan")
        );
        String requestedPrompt = firstText(
                request.get("providerPrompt"),
                request.get("videoPrompt"),
                request.get("animationPrompt"),
                request.get("videoMotionPrompt"),
                request.get("prompt"),
                scene.get("providerPrompt"),
                scene.get("videoPrompt"),
                scene.get("animationPrompt"),
                scene.get("videoMotionPrompt"),
                scene.get("prompt"),
                scene.get("seedancePrompt"),
                scene.get("action")
        );
        String directorPrompt = firstText(
                videoDirectorPlan.get("generationPrompt"),
                videoDirectorPlan.get("promptSegment")
        );
        String resolvedPrompt = directorPrompt.isBlank()
                ? requestedPrompt
                : directorPrompt + (requestedPrompt.isBlank() || requestedPrompt.equals(directorPrompt)
                ? ""
                : "\n\nAdditional approved scene direction: " + requestedPrompt);
        providerRequest.put("prompt", resolvedPrompt);
        providerRequest.put("videoPrompt", providerRequest.get("prompt"));
        providerRequest.put("animationPrompt", providerRequest.get("prompt"));
        providerRequest.put("videoDirectorPlan", videoDirectorPlan);
        providerRequest.put("masteringResolution", firstText(
                request.get("masteringResolution"),
                videoDirectorPlan.get("captureResolution"),
                videoDirectorPlan.get("masteringResolution"),
                scene.get("captureResolution"),
                "4K master minimum"
        ));
        providerRequest.put("resolution", firstText(
                request.get("resolution"),
                request.get("videoResolution"),
                scene.get("resolution"),
                "2160p"
        ));
        providerRequest.put("cameraPackage", firstText(videoDirectorPlan.get("cameraPackage"), scene.get("cameraPackage")));
        providerRequest.put("captureSettings", firstText(videoDirectorPlan.get("captureSettings"), scene.get("captureSettings")));
        providerRequest.put("lightingPlan", firstText(videoDirectorPlan.get("lightingPlan"), scene.get("lightingPlan"), scene.get("lighting")));
        providerRequest.put("directorNotes", firstText(videoDirectorPlan.get("directorNotes"), scene.get("directorNotes"), scene.get("creatorDirection")));
        providerRequest.put("imagePrompt", firstText(scene.get("imagePrompt"), scene.get("storyboardImagePrompt"), scene.get("visualPrompt")));
        providerRequest.put("shotType", firstText(scene.get("shotType"), scene.get("shot_type")));
        providerRequest.put("cameraMovement", firstText(scene.get("cameraMovement"), scene.get("cameraMove"), scene.get("motion")));
        providerRequest.put("hook", firstText(scene.get("hook"), scene.get("openingHook"), scene.get("hookLine"), scene.get("narrativeBeat"), scene.get("beatTitle"), scene.get("title")));
        providerRequest.put("retentionGoal", firstText(scene.get("retentionGoal"), scene.get("retention_goal")));
        providerRequest.put("patternInterrupt", firstText(scene.get("patternInterrupt"), scene.get("pattern_interrupt")));
        providerRequest.put("adFormat", firstText(scene.get("adFormat"), contextPayload.get("adFormat"), contextPayload.get("categoryCode")));
        providerRequest.put("adFormatKey", firstText(scene.get("adFormatKey"), contextPayload.get("adFormatKey")));
        providerRequest.put("formatPlaybook", firstMap(scene.get("formatPlaybook"), contextPayload.get("formatPlaybook"), firstMap(contextPayload.get("creativeBrief")).get("formatPlaybook")));
        providerRequest.put("sceneDetail", firstText(scene.get("sceneDetail"), scene.get("sceneDetails"), scene.get("description"), scene.get("action"), scene.get("visualPrompt")));
        providerRequest.put("backgroundDetail", firstText(scene.get("backgroundDetail"), scene.get("background"), scene.get("setting"), scene.get("location"), scene.get("environment"), scene.get("setDescription")));
        providerRequest.put("characterDetail", firstValue(scene.get("characterDetail"), scene.get("characterDetails"), scene.get("character"), scene.get("characters"), contextPayload.get("storyCharacters"), contextPayload.get("characters")));
    }

    private void resolveCastAndReferencePolicy() {
        // No structured "which characters appear in this scene" field exists on the scene JSON -
        // resolve it here by name-matching against the script's cast mappings, ephemerally per
        // request. Nothing is written back to the scene record, so this can't corrupt existing
        // scene-JSON readers if the matching ever needs to change.
        castCharactersForScene = owner.castCharactersForScene(scene, contextPayload);
        providerRequest.put("castCharacters", castCharactersForScene);
        // Set by uploadSceneReferenceImage() when the caller attached a one-off reference image
        // for this specific generation, separate from the scene's persisted product/cast images.
        providerRequest.put("adHocReferenceImageAsset", firstMap(scene.get("adHocReferenceImageAsset")));
        providerRequest.put("referenceImagePriority", firstText(scene.get("referenceImagePriority"), "combine"));
        providerRequest.put("visualTreatment", firstMap(scene.get("visualTreatment")));
        providerRequest.put("cinematicExecution", firstMap(scene.get("cinematicExecution")));
        providerRequest.put("editingNotes", firstList(scene.get("editingNotes")));
        providerRequest.put("creatorDirection", firstText(scene.get("creatorDirection"), scene.get("directorNotes")));
        providerRequest.put("durationSeconds", positiveInt(scene.get("durationSeconds"), intValue(request.get("maxClipSeconds"), 15)));
        providerRequest.put("maxClipSeconds", intValue(request.get("maxClipSeconds"), positiveInt(scene.get("maxClipSeconds"), owner.defaultMaxClipSecondsForProvider(provider))));
        providerRequest.put("aspectRatio", "horizontal".equalsIgnoreCase(firstText(request.get("screenType"), contextPayload.get("screenType"))) ? "16:9" : "9:16");
        providerRequest.put("generationMode", owner.generationModeFor(scene, contextPayload, request));
    }

    private void resolveFounderAvatarIdentity() {
        founderProfile = owner.founderAvatarProfile(request, contextPayload, firstMap(contextPayload.get("creatorContext")));
        providerRequest.put("founderAvatarProfile", founderProfile);
        providerRequest.put("founderKit", founderProfile);
        providerRequest.put("avatarPortraitAsset", firstMap(
                request.get("avatarPortraitAsset"),
                scene.get("avatarPortraitAsset"),
                founderProfile.get("avatarPortraitAsset")
        ));
        providerRequest.put("avatarTestAsset", firstMap(founderProfile.get("avatarTestAsset")));
        providerRequest.put("avatarProviderMode", owner.avatarProviderFrom(request.get("avatarProviderMode"), scene.get("avatarProviderMode"), contextPayload.get("avatarProviderMode"), founderProfile.get("avatarProviderMode")));
        providerRequest.put("avatarProvider", providerRequest.get("avatarProviderMode"));
        providerRequest.put("avatarId", firstText(request.get("avatarId"), scene.get("avatarId"), contextPayload.get("avatarId"), founderProfile.get("avatarId")));
        providerRequest.put("voiceId", firstText(request.get("voiceId"), scene.get("voiceId"), contextPayload.get("voiceId"), founderProfile.get("voiceId")));
        providerRequest.put("synthesiaAvatarId", firstText(request.get("synthesiaAvatarId"), founderProfile.get("synthesiaAvatarId"), founderProfile.get("avatarId")));
        providerRequest.put("synthesiaVoiceId", firstText(request.get("synthesiaVoiceId"), founderProfile.get("synthesiaVoiceId"), founderProfile.get("voiceId")));
        providerRequest.put("portraitEmbeddingId", firstText(request.get("portraitEmbeddingId"), founderProfile.get("portraitEmbeddingId")));
        providerRequest.put("facialFeatureEmbeddingId", firstText(request.get("facialFeatureEmbeddingId"), founderProfile.get("facialFeatureEmbeddingId")));
        providerRequest.put("voiceEmbeddingId", firstText(request.get("voiceEmbeddingId"), founderProfile.get("voiceEmbeddingId")));
        providerRequest.put("voiceProfileId", firstText(
                request.get("voiceProfileId"),
                founderProfile.get("voiceProfileId"),
                firstMap(founderProfile.get("localModels")).get("voiceProfileId")
        ));
        Map<String, Object> localModels = new LinkedHashMap<>(firstMap(
                request.get("localModels"),
                request.get("localAvatarModels"),
                founderProfile.get("localModels")
        ));
        String talkingAvatarModel = owner.normalizeLocalTalkingAvatarModel(firstText(
                request.get("talkingAvatarModel"),
                request.get("localTalkingAvatarModel"),
                localModels.get("talkingAvatarModel"),
                model
        ));
        String lipSyncModel = "fal_heygen_avatar4".equals(talkingAvatarModel)
                ? "avatar_native"
                : owner.normalizeLocalLipSyncModel(firstText(
                        request.get("lipSyncModel"),
                        request.get("localLipSyncModel"),
                        localModels.get("lipSyncModel")
                ));
        localModels.put("talkingAvatarModel", talkingAvatarModel);
        localModels.put("lipSyncModel", lipSyncModel);
        localModels.putIfAbsent(
                "avatarResolution",
                "fal_heygen_avatar4".equals(talkingAvatarModel) ? "720p" : "1080p"
        );
        localModels.putIfAbsent("talkingStyle", "stable");
        providerRequest.put("localModels", localModels);
        providerRequest.put("talkingAvatarModel", talkingAvatarModel);
        providerRequest.put("lipSyncModel", lipSyncModel);
        providerRequest.put("avatarResolution", firstText(
                request.get("avatarResolution"),
                localModels.get("avatarResolution"),
                "720p"
        ));
        providerRequest.put("talkingStyle", firstText(
                request.get("talkingStyle"),
                localModels.get("talkingStyle"),
                founderProfile.get("talkingStyle"),
                "stable"
        ));
        providerRequest.put("expression", firstText(
                request.get("expression"),
                request.get("avatarExpression"),
                scene.get("expression"),
                scene.get("emotion"),
                founderProfile.get("avatarExpression")
        ));
        providerRequest.put("avatarBackground", firstMap(
                request.get("avatarBackground"),
                scene.get("avatarBackground"),
                founderProfile.get("avatarBackground")
        ));
        providerRequest.put("avatarCaption", booleanValue(request.get("avatarCaption"), false));
        providerRequest.put("manualApprovalRequiredForFallback", booleanValue(firstValue(request.get("manualApprovalRequiredForFallback"), founderProfile.get("manualApprovalRequiredForFallback")), true));
        providerRequest.put("founderConsentConfirmed", booleanValue(firstValue(request.get("consentConfirmed"), founderProfile.get("consentConfirmed")), false));
        providerRequest.put("language", firstText(request.get("dialogueLanguage"), founderProfile.get("language"), "Hinglish"));
        providerRequest.put("languageCode", firstText(request.get("languageCode"), founderProfile.get("languageCode"), "hi-IN"));
    }

    private void resolveFinishingPlans() {
        providerRequest.put("srtFile", firstMap(request.get("srtFile"), contextPayload.get("srtFile")));
        providerRequest.put("videoPacingProfile", firstMap(request.get("videoPacingProfile"), contextPayload.get("videoPacingProfile")));
        videoFinishingPlan = owner.withAudioMixStandards(firstMap(request.get("videoFinishingPlan"), contextPayload.get("videoFinishingPlan")));
        soundDesignPlan = owner.withAudioMixStandards(firstMap(request.get("soundDesignPlan"), contextPayload.get("soundDesignPlan")));
        imageLedAdPlan = owner.imageLedAdPlan(request, contextPayload, videoFinishingPlan);
        audioProductionPlan = owner.audioProductionPlan(request, contextPayload, soundDesignPlan, videoFinishingPlan);
        editingPlan = owner.editingPlan(request, contextPayload, videoFinishingPlan, soundDesignPlan, imageLedAdPlan, audioProductionPlan);
        providerRequest.put("videoFinishingPlan", videoFinishingPlan);
        providerRequest.put("soundDesignPlan", soundDesignPlan);
        providerRequest.put("imageLedAdPlan", imageLedAdPlan);
        providerRequest.put("audioProductionPlan", audioProductionPlan);
        providerRequest.put("editingPlan", editingPlan);
        providerRequest.put("editorHandoffPlan", editingPlan);
        providerRequest.put("productCreativeEvidence", firstMap(
                scene.get("productCreativeEvidence"),
                request.get("productCreativeEvidence"),
                contextPayload.get("productCreativeEvidence")
        ));
        providerRequest.put("productShotPlan", firstMap(
                scene.get("productShotPlan"),
                request.get("productShotPlan")
        ));
        providerRequest.put("productStoryBeat", firstText(
                scene.get("productStoryBeat"),
                scene.get("narrativeBeat"),
                scene.get("purpose")
        ));
        providerRequest.put("recommendedEditingTools", firstValue(editingPlan.get("recommendedTools"), editingPlan.get("toolsToUse")));
    }

    private void resolveAudioFields() {
        String sceneSoundPrompt = firstText(
                request.get("audioDescription"),
                request.get("soundPrompt"),
                request.get("soundDesignPrompt"),
                scene.get("audioDescription"),
                scene.get("audio_description"),
                scene.get("soundPrompt"),
                scene.get("sound_prompt"),
                scene.get("soundDescription"),
                scene.get("sound_description"),
                firstMap(scene.get("soundDesign"), scene.get("sound_design")).get("description"),
                firstMap(scene.get("soundDesign"), scene.get("sound_design")).get("prompt"),
                scene.get("ambientBedDescription"),
                scene.get("syncHitDescription"),
                scene.get("backgroundMusicCue")
        );
        providerRequest.put("audioDescription", sceneSoundPrompt);
        providerRequest.put("soundPrompt", sceneSoundPrompt);
        providerRequest.put("soundDesignPrompt", sceneSoundPrompt);
        providerRequest.put("soundDesign", firstValue(
                request.get("soundDesign"),
                scene.get("soundDesign"),
                scene.get("sound_design"),
                Map.of()
        ));
        providerRequest.put("ambientBedDescription", firstText(
                request.get("ambientBedDescription"),
                scene.get("ambientBedDescription"),
                scene.get("ambient_bed_description")
        ));
        providerRequest.put("syncHitDescription", firstText(
                request.get("syncHitDescription"),
                scene.get("syncHitDescription"),
                scene.get("sync_hit_description")
        ));
        providerRequest.put("backgroundMusicCue", firstText(
                request.get("backgroundMusicCue"),
                scene.get("backgroundMusicCue"),
                scene.get("background_music_cue")
        ));
        providerRequest.put("generateAudio", booleanValue(firstValue(
                request.get("generateAudio"),
                request.get("generate_audio"),
                scene.get("generateAudio"),
                scene.get("generate_audio")
        ), !sceneSoundPrompt.isBlank()));
        providerRequest.put("audioMixStandards", owner.audioMixStandards(
                request.get("audioMixStandards"),
                contextPayload.get("audioMixStandards"),
                videoFinishingPlan.get("audioMixStandards"),
                soundDesignPlan.get("audioMixStandards")
        ));
    }

    private void resolveReferenceImageModePolicy() {
        providerRequest.put("storyboardReferenceMode", firstText(request.get("storyboardReferenceMode"), request.get("referenceImageMode"), contextPayload.get("storyboardReferenceMode"), contextPayload.get("referenceImageMode"), videoFinishingPlan.get("referenceImageMode"), imageLedAdPlan.get("referenceImageMode"), "prompt_only"));
        providerRequest.put("referenceImageMode", firstText(request.get("referenceImageMode"), contextPayload.get("referenceImageMode"), videoFinishingPlan.get("referenceImageMode"), imageLedAdPlan.get("referenceImageMode"), "prompt_only"));
        providerRequest.put("requireReferenceImage", booleanValue(firstValue(request.get("requireReferenceImage"), request.get("requireImageAnchors"), contextPayload.get("requireImageAnchors"), videoFinishingPlan.get("requireImageAnchors"), imageLedAdPlan.get("requireImageAnchors")), false));
    }

    private void resolveDialogueAndConsistency() {
        String sceneDialogueScript = owner.dialogueTextForScene(scene);
        providerRequest.put("dialogueScript", sceneDialogueScript);
        providerRequest.put("exactDialogue", sceneDialogueScript);
        providerRequest.put("dialogueCoverageRequired", !sceneDialogueScript.isBlank());
        providerRequest.put("dialogueCoveragePolicy", "speak_every_word_in_order_without_paraphrase_when_native_audio_is_supported");
        providerRequest.put("referenceImageDetails", firstText(
                scene.get("referenceImageDetails"),
                request.get("referenceImageDetails"),
                request.get("productReferenceDetails"),
                contextPayload.get("referenceImageDetails")
        ));
        consistencyBible = owner.enrichedVideoConsistencyBible(scene, request, contextPayload);
        providerRequest.put("videoConsistencyBible", consistencyBible);
    }

    private void resolveSeedAndNegativePrompt() {
        Map<String, Object> seedanceStrategy = firstMap(request.get("seedancePromptStrategy"), contextPayload.get("seedancePromptStrategy"));
        seriesSeed = longValue(firstValue(request.get("seriesSeed"), contextPayload.get("seriesSeed")), 0);
        if (seriesSeed <= 0) {
            seriesSeed = deterministicSeed(firstText(contextPayload.get("runId"), contextPayload.get("scriptId"), contextPayload.get("title")), "series");
        }
        sceneSeed = longValue(firstValue(request.get("seed"), scene.get("seed")), 0);
        if (sceneSeed <= 0) {
            sceneSeed = deterministicSeed(String.valueOf(seriesSeed), firstText(scene.get("id"), scene.get("sceneId"), scene.get("sceneNumber")));
        }
        noHumans = booleanValue(firstValue(request.get("noHumans"), scene.get("noHumans"), contextPayload.get("noHumans")), false);
        String negativePrompt = firstText(
                request.get("negativePrompt"),
                scene.get("negativePrompt"),
                consistencyBible.get("negativePrompt"),
                "no face drift, no wardrobe change, no random new actor, no changed room layout, no wrong aspect ratio, no unreadable text, no watermark, no random subtitles, no extra limbs"
        );
        if (noHumans && !negativePrompt.toLowerCase(Locale.ROOT).contains("no people")) {
            negativePrompt += ", no people, no person, no face, no hands, no arms, no human body, no human silhouette, no human reflection, no presenter, no crowd";
        }
        providerRequest.put("noHumans", noHumans);
        providerRequest.put("captionsEnabled", booleanValue(request.get("captionsEnabled"), true));
        providerRequest.put("seedancePromptStrategy", seedanceStrategy);
        providerRequest.put("seriesSeed", seriesSeed);
        providerRequest.put("seed", sceneSeed);
        providerRequest.put("negativePrompt", negativePrompt);
        providerRequest.put("visualConsistencyPrompt", firstText(
                seedanceStrategy.get("globalConsistencyPrompt"),
                consistencyBible.get("globalConsistencyPrompt"),
                toJson(consistencyBible)
        ));
        providerRequest.put("pacingPrompt", firstText(scene.get("pacingPrompt"), seedanceStrategy.get("fastPacedPrompt"), seedanceStrategy.get("slowPacedPrompt")));
        providerRequest.put("srtCues", firstList(scene.get("srtCues"), request.get("srtCues"), contextPayload.get("srtCues")));
        providerRequest.put("captionTrack", firstList(scene.get("captionTrack")));
    }

    /**
     * Gathers every reference-image source this scene has - a generic scene image anchor, the
     * product-CGI frame (generated + canonical), and named cast faces - and combines them. These
     * are additive context, not alternative strategies to pick between: a product-led shot with an
     * assigned cast member legitimately needs both the product frame (composition/product identity)
     * AND that person's face (identity only) at the same time. Forcing product-vs-cast into a single
     * either/or selection was exactly the bug fixed earlier this session (seedanceProductReferenceMode
     * used to be gated on noHumans, which is false the instant a cast character is present).
     */
    private void resolveReferenceImages() {
        List<String> referenceImageUrls = owner.referenceImageUrlsForScene(scene, request, contextPayload);
        providerRequest.put("referenceImageUrl", firstText(referenceImageUrls.isEmpty() ? null : referenceImageUrls.get(0)));
        providerRequest.put("referenceImageUrls", referenceImageUrls);
        List<Map<String, Object>> productImageAssets = owner.productImageAssetsForScene(scene, request, contextPayload);
        providerRequest.put("productImageAssets", productImageAssets);
        providerRequest.put("referenceImageAssets", productImageAssets);
        List<Map<String, Object>> generatedProductSceneAssets = owner.generatedProductSceneImageAssets(scene, request);
        List<Map<String, Object>> canonicalProductAssets = owner.canonicalProductImageAssets(request, contextPayload, productImageAssets);
        List<String> generatedProductSceneUrls = owner.generatedProductSceneImageUrls(scene, request, generatedProductSceneAssets);
        List<String> canonicalProductUrls = owner.canonicalProductImageUrls(request, contextPayload, canonicalProductAssets);
        List<Map<String, Object>> seedanceReferenceAssets = new ArrayList<>();
        generatedProductSceneAssets.forEach(asset -> owner.addProductImageAssets(seedanceReferenceAssets, asset));
        canonicalProductAssets.forEach(asset -> owner.addProductImageAssets(seedanceReferenceAssets, asset));
        List<String> seedanceReferenceUrls = new ArrayList<>();
        generatedProductSceneUrls.forEach(url -> owner.addReferenceImageUrl(seedanceReferenceUrls, url));
        canonicalProductUrls.forEach(url -> owner.addReferenceImageUrl(seedanceReferenceUrls, url));
        // Not gated on noHumans: a product-led shot with an assigned cast member (a model wearing
        // or holding the product) still has a real approved CGI product frame that must drive
        // composition/product identity - it just ALSO needs that model's face (castFaceReferenceMode
        // below, independent of this flag). Gating this on noHumans used to force every shot with
        // a cast character out of product-reference mode entirely, dropping the product frame and
        // falling back to the raw cast photo alone as the reference.
        boolean seedanceProductReferenceMode = booleanValue(firstValue(
                request.get("productLed"),
                request.get("imageLedAdMode"),
                imageLedAdPlan.get("enabled"),
                scene.get("productCgiScene")
        ), false)
                && (!generatedProductSceneAssets.isEmpty() || !generatedProductSceneUrls.isEmpty())
                && (!canonicalProductAssets.isEmpty() || !canonicalProductUrls.isEmpty());
        providerRequest.put("generatedProductImageAssets", generatedProductSceneAssets);
        providerRequest.put("generatedProductImageUrls", generatedProductSceneUrls);
        providerRequest.put("generatedProductImageUrl", firstText(generatedProductSceneUrls.isEmpty() ? null : generatedProductSceneUrls.get(0)));
        providerRequest.put("canonicalProductImageAssets", canonicalProductAssets);
        providerRequest.put("canonicalProductImageUrls", canonicalProductUrls);
        providerRequest.put("seedanceReferenceImageAssets", seedanceReferenceAssets);
        providerRequest.put("seedanceReferenceImageUrls", seedanceReferenceUrls);
        providerRequest.put("seedanceReferenceToVideo", seedanceProductReferenceMode);
        providerRequest.put("seedanceReferenceMode", seedanceProductReferenceMode ? "product_cgi_multi_reference" : "single_frame_or_text");
        providerRequest.put("seedanceReferencePromptPolicy", seedanceProductReferenceMode
                ? "@Image1 is the approved CGI shot frame. @Image2 and later images are canonical source-product references used only to lock product identity."
                : "");
        // Cast face identity: one labeled reference image per cast member matched into this scene
        // (castCharactersForScene, resolved in resolveCastAndReferencePolicy()), each already
        // carrying a freshly-signed referenceImageUrl from CharacterCastMappingService.castPayloadFor().
        List<Map<String, Object>> castFaces = castCharactersForScene.stream()
                .filter(character -> !stringValue(character.get("referenceImageUrl"), "").isBlank())
                .toList();
        providerRequest.put("castFaces", castFaces);
        providerRequest.put("castFaceImageUrls", castFaces.stream()
                .map(character -> stringValue(character.get("referenceImageUrl"), ""))
                .toList());
        providerRequest.put("castFaceReferenceMode", !noHumans && !castFaces.isEmpty());
        providerRequest.put("storyboardVisualPolicy", "Storyboard sketches are planning references only. Never attach, animate, reproduce, or render a storyboard card, sketch, panel, or drawing in the generated video. Use a supplied product visual anchor only when it is explicitly marked product_visual_anchor.");
    }

    private void resolveConsistencyStrategyAndProviderEndpoint() {
        providerRequest.put("consistencyStrategy", Map.of(
                "seriesSeed", seriesSeed,
                "sceneSeed", sceneSeed,
                "strategy", "deterministic_scene_seed_plus_prompt_locks",
                "locks", List.of("character_identity", "wardrobe", "set_geography", "lighting", "camera_language", "srt_cues", "adjacent_scene_continuity")
        ));
        providerRequest.put("rateLimitPolicy", owner.rateLimitPolicy(provider));
        ProviderEndpoint endpoint = ProviderEndpoint.forProvider(provider, model, owner);
        providerRequest.put("baseUrl", endpoint.baseUrl());
        providerRequest.put("apiKeyEnv", endpoint.apiKeyEnv());
        if (!endpoint.operation().isBlank()) {
            providerRequest.put("operation", endpoint.operation());
        }
        if (!endpoint.pollOperation().isBlank()) {
            providerRequest.put("pollOperation", endpoint.pollOperation());
        }
    }

    /**
     * Strategy-shaped lookup replacing the original if/else-if chain on `provider` - exactly one
     * config applies per provider string, unlike the reference-image gathering above where several
     * sources genuinely combine.
     */
    private record ProviderEndpoint(String baseUrl, String apiKeyEnv, String operation, String pollOperation) {
        static ProviderEndpoint forProvider(String provider, String model, ScreenplayVideoService owner) {
            return switch (provider) {
                case "google_veo" -> new ProviderEndpoint(
                        owner.googleVeoBaseUrl(model),
                        "GOOGLE_VEO_API_KEY, GOOGLE_API_KEY, GEMINI_API_KEY, or CREATOR_GEMINI_API_KEY",
                        "predictLongRunning",
                        "operations.get"
                );
                case "gemini_omni" -> new ProviderEndpoint(
                        envString("GEMINI_OMNI_BASE_URL", envString("GOOGLE_OMNI_BASE_URL", "https://generativelanguage.googleapis.com/v1beta")),
                        "GEMINI_OMNI_API_KEY, GOOGLE_OMNI_API_KEY, GOOGLE_API_KEY, GEMINI_API_KEY, or CREATOR_GEMINI_API_KEY",
                        "models.generateContent",
                        "inline_video_or_file_download"
                );
                case "synthesia" -> new ProviderEndpoint(
                        envString("SYNTHESIA_BASE_URL", "https://api.synthesia.io"),
                        "SYNTHESIA_API_KEY",
                        "create_avatar_video",
                        "get_video_status"
                );
                case "dalai_llama" -> new ProviderEndpoint(
                        envString("DALAI_LLAMA_AI_SERVICE_URL", envString("AI_SERVICE_URL", "http://ai-service.apps.svc.cluster.local:8601")),
                        "optional DALAI_LLAMA_AI_SERVICE_API_KEY",
                        "local_open_source_avatar_scene",
                        "get_local_avatar_job"
                );
                case "seedance" -> new ProviderEndpoint(
                        envString("SEEDANCE_BASE_URL", "https://queue.fal.run"),
                        "FAL_KEY or SEEDANCE_API_KEY",
                        "fal_queue_submit",
                        "fal_queue_status_then_response"
                );
                default -> new ProviderEndpoint(
                        envString("OMINI_BASE_URL", envString("OMNI_BASE_URL", "")),
                        "OMINI_API_KEY",
                        "",
                        ""
                );
            };
        }
    }
}
