package com.dalai.llama.creator.service;

import com.dalai.llama.creator.domain.entity.CreatorScript;
import com.dalai.llama.creator.domain.entity.CreatorScriptShotPlan;
import com.dalai.llama.creator.repository.CreatorScriptShotPlanRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static com.dalai.llama.creator.service.screenplayvideo.MapCoercion.*;

/**
 * Extraction of what used to be ScreenplayVideoService.buildInitialRun() plus the no-human
 * product-CGI scene-planning cluster it calls (enrichProductCgiScenePlan and everything under it) -
 * ~900 lines, previously the plan's "InitialRunAssemblyService" row. buildInitialRun has exactly one
 * caller in ScreenplayVideoService, and the product-CGI cluster (enrichProductCgiScenePlan,
 * isNoHumanProductCgiFlow, productCgiImagePrompt, productCgiMotionPrompt, productCreativeEvidence,
 * putProductEvidence, productShotImageBrief, appendPromptClause) is called from nowhere else in the
 * class - a genuinely closed subgraph, unlike the DialogueVoiceCloner methods.
 *
 * <p>Still takes the ProviderRequestBuilder/FounderSceneAudioCloner shape (same package, owner
 * back-reference) rather than a screenplayvideo/ interface: buildInitialRun's per-scene assembly
 * loop calls into roughly two dozen other ScreenplayVideoService collaborators (founderAvatarProfile,
 * sourceScenes, buildRagContext, buildProviderRequestForScene, renderManifest, ...) that are used
 * throughout the rest of the class too, so they stay there widened to package-private rather than
 * duplicated or forced behind new single-purpose interfaces.
 */
final class InitialRunAssembler {

    private final ScreenplayVideoService owner;
    private final CreatorScriptShotPlanRepository shotPlanRepository;

    InitialRunAssembler(ScreenplayVideoService owner, CreatorScriptShotPlanRepository shotPlanRepository) {
        this.owner = owner;
        this.shotPlanRepository = shotPlanRepository;
    }

    Map<String, Object> buildInitialRun(
            CreatorScript script,
            Map<String, Object> request,
            UUID runId,
            UUID jobId,
            String provider,
            String model,
            int maxClipSeconds
    ) {
        Map<String, Object> scriptPayload = copyMap(script.getScriptPayload());
        Map<String, Object> creatorContext = firstMap(scriptPayload.get("creatorContext"));
        boolean prepareOnly = booleanValue(request.get("prepareOnly"), false)
                || "scene_by_scene".equalsIgnoreCase(firstText(request.get("generationWorkflow")));
        String sourceDialogueLanguage = firstText(
                request.get("sourceDialogueLanguage"),
                script.getDialogueLanguage(),
                scriptPayload.get("dialogueLanguage"),
                "Hinglish"
        );
        String dialogueLanguage = firstText(
                request.get("dialogueLanguage"),
                request.get("language"),
                sourceDialogueLanguage
        );
        String dialogueLanguageCode = firstText(
                request.get("languageCode"),
                owner.languageCodeFor(dialogueLanguage),
                "hi-IN"
        );
        request.put("dialogueLanguage", dialogueLanguage);
        request.put("language", dialogueLanguage);
        request.put("languageCode", dialogueLanguageCode);
        List<String> productReferenceImageUrls = owner.productReferenceImageUrls(request, scriptPayload, creatorContext);
        List<Map<String, Object>> productReferenceImageAssets = owner.productReferenceImageAssets(request, scriptPayload, creatorContext);
        Map<String, Object> founderAvatarProfile = owner.founderAvatarProfile(request, scriptPayload, creatorContext);
        if (!founderAvatarProfile.isEmpty()) {
            founderAvatarProfile.put("language", dialogueLanguage);
            founderAvatarProfile.put("languageCode", dialogueLanguageCode);
        }
        String referenceImageDetails = firstText(
                request.get("referenceImageDetails"),
                request.get("productReferenceDetails"),
                request.get("referenceDetails"),
                scriptPayload.get("referenceImageDetails"),
                creatorContext.get("referenceImageDetails")
        );
        String productionStyle = owner.normalizeProductionStyle(firstText(request.get("productionStyle"), scriptPayload.get("productionStyle")));
        String hybridSceneMode = owner.normalizeFounderHybridSceneMode(firstText(request.get("hybridSceneMode"), scriptPayload.get("hybridSceneMode")));
        boolean fullFounderMode = "full_founder".equals(hybridSceneMode);
        boolean useSceneDialogue = booleanValue(request.get("useSceneDialogue"), false);
        String avatarScriptOverride = useSceneDialogue
                ? ""
                : firstText(
                        request.get("avatarScriptOverride"),
                        request.get("avatarScript"),
                        fullFounderMode ? request.get("spokenText") : null,
                        fullFounderMode ? founderAvatarProfile.get("avatarScript") : null,
                        fullFounderMode ? founderAvatarProfile.get("spokenText") : null
                );
        List<Map<String, Object>> screenplayScenes = owner.sourceScenes(script, request);
        if (fullFounderMode && !avatarScriptOverride.isBlank()) {
            screenplayScenes = owner.avatarScriptScenes(screenplayScenes, avatarScriptOverride);
            request.put("avatarScriptOverride", avatarScriptOverride);
            request.put("avatarScriptApplied", true);
        }
        boolean dialogueLocalizationRequested = booleanValue(
                request.get("autoTranslateDialogue"),
                !owner.sameLanguage(sourceDialogueLanguage, dialogueLanguage)
        ) && !owner.sameLanguage(sourceDialogueLanguage, dialogueLanguage);
        if (dialogueLocalizationRequested) {
            screenplayScenes = owner.localizeDialogueScenes(
                    script,
                    screenplayScenes,
                    sourceDialogueLanguage,
                    dialogueLanguage,
                    dialogueLanguageCode,
                    jobId
            );
        }
        List<Map<String, Object>> sourceScenes = prepareOnly
                ? screenplayScenes
                : owner.splitScenesForModelCapability(screenplayScenes, maxClipSeconds);
        boolean founderLedHybridEnabled = booleanValue(
                firstValue(request.get("founderLedHybridEnabled"), scriptPayload.get("founderLedHybridEnabled")),
                !founderAvatarProfile.isEmpty()
        ) || fullFounderMode;
        if (!prepareOnly && fullFounderMode && founderAvatarProfile.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Upload a founder source video before generating a Full Founder video."
            );
        }
        boolean founderConsentConfirmed = booleanValue(
                firstValue(
                        request.get("founderConsentConfirmed"),
                        request.get("consentConfirmed"),
                        founderAvatarProfile.get("consentConfirmed")
                ),
                false
        );
        if (!prepareOnly && founderLedHybridEnabled && !founderAvatarProfile.isEmpty() && !founderConsentConfirmed) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Founder consent must be confirmed before generating avatar scenes."
            );
        }
        String founderAvatarProviderMode = owner.avatarProviderFrom(
                request.get("avatarProviderMode"),
                founderAvatarProfile.get("avatarProviderMode")
        );
        Map<String, Object> founderLocalModels = firstMap(
                request.get("localModels"),
                founderAvatarProfile.get("localModels")
        );
        String founderTalkingAvatarModel = owner.normalizeLocalTalkingAvatarModel(firstText(
                request.get("talkingAvatarModel"),
                founderLocalModels.get("talkingAvatarModel")
        ));
        if (!prepareOnly
                && "hybrid".equals(productionStyle)
                && founderLedHybridEnabled
                && !founderAvatarProfile.isEmpty()
                && "dalai_llama".equals(founderAvatarProviderMode)
                && !"fal_heygen_avatar4".equals(founderTalkingAvatarModel)
                && !"APPROVED".equalsIgnoreCase(firstText(founderAvatarProfile.get("avatarPreviewStatus")))) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Create and approve the portrait avatar quality test before running the founder video pipeline."
            );
        }
        List<Map<String, Object>> sceneRows = new ArrayList<>();
        int runningStart = 0;
        for (int index = 0; index < sourceScenes.size(); index++) {
            Map<String, Object> source = copyMap(sourceScenes.get(index));
            Map<String, Object> requestSceneOverride = owner.requestSceneOverride(request, source, index + 1);
            if (!requestSceneOverride.isEmpty()) {
                source.putAll(requestSceneOverride);
            }
            int sceneNumber = intValue(firstValue(source.get("sceneNumber"), source.get("scene_number"), source.get("shotNumber"), source.get("shot_number")), index + 1);
            int durationSeconds = positiveInt(firstValue(source.get("durationSeconds"), source.get("duration_seconds"), source.get("duration")), maxClipSeconds);
            durationSeconds = Math.max(1, Math.min(Math.max(durationSeconds, owner.estimatedDialogueSeconds(source)), maxClipSeconds));
            Map<String, Object> scene = new LinkedHashMap<>(source);
            String sceneId = owner.sceneIdFor(source, sceneNumber);
            scene.put("id", sceneId);
            scene.put("sceneId", sceneId);
            scene.put("sceneNumber", sceneNumber);
            scene.put("shotNumber", intValue(firstValue(source.get("shotNumber"), source.get("shot_number")), sceneNumber));
            scene.put("title", firstText(source.get("title"), source.get("beatTitle"), source.get("narrativeBeat"), "Scene " + sceneNumber));
            scene.put("durationSeconds", durationSeconds);
            scene.put("startSeconds", runningStart);
            scene.put("endSeconds", runningStart + durationSeconds);
            scene.put("startTime", owner.srtTime(runningStart));
            scene.put("endTime", owner.srtTime(runningStart + durationSeconds));
            scene.put("status", "PLANNED");
            String sceneGenerationMode = owner.generationModeFor(source, request, request);
            if (owner.shouldAutoAssignFounderAvatarScene(
                    sourceScenes,
                    source,
                    index,
                    request,
                    founderAvatarProfile,
                    productionStyle,
                    hybridSceneMode,
                    founderLedHybridEnabled
            )) {
                sceneGenerationMode = "talking_head";
                source.put("generationMode", sceneGenerationMode);
                scene.put("avatarSceneSelectionReason", fullFounderMode ? "full_founder_all_scenes" : "auto_founder_start_middle_end");
            }
            String sceneProvider = owner.providerForSceneGeneration(source, request, request);
            String sceneModel = owner.modelForSceneGeneration(sceneProvider, source, request, request);
            scene.put("provider", sceneProvider);
            scene.put("targetProvider", sceneProvider);
            scene.put("model", sceneModel);
            scene.put("brollProvider", provider);
            scene.put("brollModel", model);
            scene.put("maxClipSeconds", maxClipSeconds);
            scene.put("generationMode", sceneGenerationMode);
            if ("talking_head".equals(sceneGenerationMode)) {
                scene.put("avatarProviderMode", owner.avatarProviderFrom(request.get("avatarProviderMode"), founderAvatarProfile.get("avatarProviderMode")));
                scene.put("founderAvatarProfile", founderAvatarProfile);
            }
            scene = enrichProductCgiScenePlan(
                    scene,
                    sourceScenes,
                    index,
                    scriptPayload,
                    request,
                    referenceImageDetails,
                    script.getId()
            );
            scene.put("providerPrompt", owner.providerPromptFor(source, scriptPayload, request));
            if (booleanValue(scene.get("productCgiScene"), false)) {
                scene.put("providerPrompt", firstText(
                        scene.get("videoMotionPrompt"),
                        scene.get("productMotionPrompt"),
                        scene.get("providerPrompt")
                ));
            }
            scene.put("prompt", firstText(scene.get("providerPrompt"), source.get("seedancePrompt"), source.get("visualPrompt"), source.get("action"), source.get("description")));
            scene.put("brollStyle", firstText(source.get("brollStyle"), source.get("broll_style"), request.get("brollStyle")));
            scene.put("captionStyle", firstText(source.get("captionStyle"), source.get("caption_style"), request.get("captionStyle")));
            scene.put("dialogueScript", owner.dialogueTextForScene(scene));
            scene.put("dialogueCoverageRequired", !firstText(scene.get("dialogueScript")).isBlank());
            scene.put("referenceImageDetails", referenceImageDetails);
            scene.put("ragContext", owner.buildRagContext(script, null, sourceScenes, index, request));
            scene.put("providerRequest", owner.buildProviderRequestForScene(sceneProvider, sceneModel, scene, request, scriptPayload));
            sceneRows.add(scene);
            runningStart += durationSeconds;
        }

        Map<String, Object> run = new LinkedHashMap<>();
        run.put("runId", runId.toString());
        run.put("id", runId.toString());
        run.put("scriptId", script.getId().toString());
        run.put("jobId", jobId.toString());
        run.put("tenantId", script.getTenantId());
        run.put("userId", script.getUserId());
        run.put("projectId", script.getProjectId() == null ? null : script.getProjectId().toString());
        run.put("title", defaultString(script.getTitle(), stringValue(scriptPayload.get("projectTitle"), "Screenplay video")));
        run.put("status", "PLANNED");
        run.put("provider", provider);
        run.put("model", model);
        run.put("providerOptions", owner.providerOptions());
        run.put("modelOptions", owner.modelOptions(provider));
        run.put("maxClipSeconds", maxClipSeconds);
        run.put("durationSeconds", positiveInt(firstValue(request.get("targetDurationSeconds"), request.get("durationSeconds"), script.getDurationSeconds()), runningStart));
        run.put("screenType", firstText(request.get("screenType"), script.getScreenType(), scriptPayload.get("screenType")));
        run.put("productionStyle", productionStyle);
        run.put("hybridSceneMode", hybridSceneMode);
        run.put("useSceneDialogue", useSceneDialogue);
        run.put("noHumans", booleanValue(firstValue(request.get("noHumans"), scriptPayload.get("noHumans")), false));
        run.put("shotPlanningMode", firstText(request.get("shotPlanningMode"), scriptPayload.get("shotPlanningMode")));
        run.put("brollStyle", firstText(request.get("brollStyle"), scriptPayload.get("brollStyle")));
        run.put("captionStyle", firstText(request.get("captionStyle"), scriptPayload.get("captionStyle")));
        run.put("sourceDialogueLanguage", sourceDialogueLanguage);
        run.put("dialogueLanguage", dialogueLanguage);
        run.put("languageCode", dialogueLanguageCode);
        run.put("dialogueLocalizationRequested", dialogueLocalizationRequested);
        run.put("dialogueLocalizationStatus", dialogueLocalizationRequested ? "COMPLETED" : "NOT_REQUIRED");
        run.put("founderLedHybridEnabled", founderLedHybridEnabled);
        run.put("founderAvatarProfile", founderAvatarProfile);
        run.put("founderKit", founderAvatarProfile);
        run.put("avatarProviderMode", founderAvatarProviderMode);
        run.put("avatarProvider", run.get("avatarProviderMode"));
        run.put("voiceProvider", firstText(request.get("voiceProvider"), scriptPayload.get("voiceProvider"), owner.avatarVoiceProvider(founderAvatarProfile), "google_chirp"));
        run.put("avatarId", firstText(request.get("avatarId"), founderAvatarProfile.get("avatarId")));
        run.put("voiceId", firstText(request.get("voiceId"), founderAvatarProfile.get("voiceId")));
        run.put("portraitEmbeddingId", firstText(founderAvatarProfile.get("portraitEmbeddingId")));
        run.put("facialFeatureEmbeddingId", firstText(founderAvatarProfile.get("facialFeatureEmbeddingId")));
        run.put("voiceEmbeddingId", firstText(founderAvatarProfile.get("voiceEmbeddingId")));
        run.put("productImageUrls", productReferenceImageUrls);
        run.put("referenceImageUrls", productReferenceImageUrls);
        run.put("productImageAssets", productReferenceImageAssets);
        run.put("referenceImageAssets", productReferenceImageAssets);
        run.put("referenceImageDetails", referenceImageDetails);
        run.put("storyCharacters", firstList(scriptPayload.get("storyCharacters"), scriptPayload.get("characters"), creatorContext.get("storyCharacters"), creatorContext.get("characters")));
        run.put("characterCastMappings", firstList(scriptPayload.get("characterCastMappings"), creatorContext.get("characterCastMappings")));
        run.put("availableActors", firstList(scriptPayload.get("availableActors"), creatorContext.get("availableActors"), firstMap(creatorContext.get("castPlan")).get("actors")));
        run.put("dialogueVoiceProfile", owner.dialogueVoiceProfile(request, run, scriptPayload));
        run.put("videoPacingProfile", firstMap(request.get("videoPacingProfile"), scriptPayload.get("videoPacingProfile")));
        run.put("videoConsistencyBible", firstMap(request.get("videoConsistencyBible"), scriptPayload.get("videoConsistencyBible")));
        run.put("seedancePromptStrategy", firstMap(request.get("seedancePromptStrategy"), scriptPayload.get("seedancePromptStrategy")));
        run.put("srt", firstText(request.get("srt"), scriptPayload.get("srt")));
        run.put("srtFile", firstMap(request.get("srtFile"), scriptPayload.get("srtFile")));
        run.put("srtCues", firstList(request.get("srtCues"), scriptPayload.get("srtCues")));
        Map<String, Object> videoFinishingPlan = owner.withAudioMixStandards(firstMap(request.get("videoFinishingPlan"), scriptPayload.get("videoFinishingPlan")));
        Map<String, Object> soundDesignPlan = owner.withAudioMixStandards(firstMap(request.get("soundDesignPlan"), scriptPayload.get("soundDesignPlan")));
        Map<String, Object> imageLedAdPlan = owner.imageLedAdPlan(request, scriptPayload, videoFinishingPlan);
        Map<String, Object> audioProductionPlan = owner.audioProductionPlan(request, scriptPayload, soundDesignPlan, videoFinishingPlan);
        Map<String, Object> editingPlan = owner.editingPlan(request, scriptPayload, videoFinishingPlan, soundDesignPlan, imageLedAdPlan, audioProductionPlan);
        run.put("videoFinishingPlan", videoFinishingPlan);
        run.put("soundDesignPlan", soundDesignPlan);
        run.put("imageLedAdPlan", imageLedAdPlan);
        run.put("audioProductionPlan", audioProductionPlan);
        run.put("editingPlan", editingPlan);
        run.put("editorHandoffPlan", editingPlan);
        run.put("recommendedEditingTools", firstValue(editingPlan.get("recommendedTools"), editingPlan.get("toolsToUse")));
        run.put("audioMixStandards", owner.audioMixStandards(videoFinishingPlan.get("audioMixStandards"), soundDesignPlan.get("audioMixStandards"), request.get("audioMixStandards"), scriptPayload.get("audioMixStandards")));
        run.put("storyboardReferenceMode", firstText(request.get("storyboardReferenceMode"), request.get("referenceImageMode"), videoFinishingPlan.get("referenceImageMode"), imageLedAdPlan.get("referenceImageMode"), "prompt_only"));
        run.put("referenceImageMode", firstText(
                request.get("referenceImageMode"),
                productReferenceImageUrls.isEmpty() ? null : "product_motion_anchor",
                videoFinishingPlan.get("referenceImageMode"),
                imageLedAdPlan.get("referenceImageMode"),
                "prompt_only"
        ));
        run.put("requireImageAnchors", booleanValue(firstValue(request.get("requireImageAnchors"), videoFinishingPlan.get("requireImageAnchors"), imageLedAdPlan.get("requireImageAnchors")), false));
        run.put("burnCaptions", booleanValue(firstValue(request.get("burnCaptions"), mapValue(request.get("videoFinishingPlan")).get("burnCaptions")), true));
        run.put("useMixedAudio", booleanValue(firstValue(request.get("useMixedAudio"), mapValue(request.get("videoFinishingPlan")).get("useMixedAudio")), true));
        run.put("scenes", sceneRows);
        run.put("sceneClips", sceneRows);
        run.put("timeline", Map.of(
                "durationSeconds", runningStart,
                "maxClipSeconds", maxClipSeconds,
                "sceneCount", sceneRows.size()
        ));
        run.put("renderManifest", owner.renderManifest(provider, model, request, scriptPayload, sceneRows));
        run.put("createdAt", OffsetDateTime.now().toString());
        run.put("updatedAt", OffsetDateTime.now().toString());
        return run;
    }

    private Map<String, Object> enrichProductCgiScenePlan(
            Map<String, Object> sourceScene,
            List<Map<String, Object>> allScenes,
            int sceneIndex,
            Map<String, Object> scriptPayload,
            Map<String, Object> request,
            String referenceImageDetails,
            UUID scriptId
    ) {
        Map<String, Object> scene = copyMap(sourceScene);
        if (!isNoHumanProductCgiFlow(scene, scriptPayload, request)) {
            return scene;
        }

        // Stage 1 shot plans aren't attached to scenes until enrichScenesWithStoryboardReferences
        // runs later in the pipeline (attachProductionPlanTags), which is AFTER this method - so
        // scene.get("productShotType") would always be blank here without this direct lookup.
        // Same lookup pattern attachProductionPlanTags already uses elsewhere in this class.
        int shotNumber = intValue(firstValue(scene.get("shotNumber"), scene.get("sceneNumber")), sceneIndex + 1);
        if (scriptId != null) {
            CreatorScriptShotPlan plan = shotPlanRepository
                    .findByScriptIdAndShotNumberAndStyleKey(scriptId, shotNumber, ProductionPlanTagService.DEFAULT_STYLE_KEY)
                    .orElse(null);
            String productShotType = plan == null ? "" : stringValue(mapValue(plan.getStoryboardTag()).get("productShotType"), "");
            if (!productShotType.isBlank()) {
                scene.put("productShotType", productShotType);
            }
        }

        Map<String, Object> safeScript = scriptPayload == null ? Map.of() : scriptPayload;
        Map<String, Object> creatorContext = firstMap(safeScript.get("creatorContext"));
        Map<String, Object> productBrief = firstMap(
                safeScript.get("productIntelligence"),
                safeScript.get("productIntelligenceBrief"),
                creatorContext.get("productIntelligence"),
                creatorContext.get("productIntelligenceBrief")
        );
        Map<String, Object> productUnderstanding = firstMap(productBrief.get("productUnderstanding"));
        String productName = firstText(
                productBrief.get("productName"),
                productBrief.get("name"),
                productUnderstanding.get("productName"),
                productUnderstanding.get("name"),
                safeScript.get("productName"),
                safeScript.get("projectTitle"),
                "the supplied product"
        );
        Map<String, Object> productCreativeEvidence = productCreativeEvidence(
                productBrief,
                productUnderstanding,
                safeScript,
                creatorContext,
                request
        );
        String productStoryBeat = firstText(
                scene.get("productStoryBeat"),
                scene.get("narrativeBeat"),
                scene.get("purpose"),
                scene.get("retentionGoal"),
                scene.get("action"),
                "Advance the approved product story with one clear visual proof beat."
        );
        String previousShotBrief = productShotImageBrief(allScenes, sceneIndex - 1);
        String nextShotBrief = productShotImageBrief(allScenes, sceneIndex + 1);
        String imagePrompt = productCgiImagePrompt(
                scene,
                productName,
                productCreativeEvidence,
                productStoryBeat,
                referenceImageDetails,
                previousShotBrief,
                nextShotBrief
        );
        String motionPrompt = productCgiMotionPrompt(
                scene,
                productName,
                productCreativeEvidence,
                productStoryBeat,
                nextShotBrief
        );
        String negativePrompt = appendPromptClause(
                firstText(scene.get("negativePrompt"), scene.get("negative_prompt")),
                "no people, no person, no face, no hands, no arms, no body, no human silhouette, no human reflection, no presenter, no crowd, no package mutation, no logo drift, no label change, no warped product, no duplicate product, no invented text, no watermark"
        );

        Map<String, Object> productShotPlan = new LinkedHashMap<>(firstMap(
                scene.get("productShotPlan"),
                scene.get("product_shot_plan")
        ));
        productShotPlan.put("shotNumber", intValue(firstValue(scene.get("shotNumber"), scene.get("sceneNumber")), sceneIndex + 1));
        productShotPlan.put("shotType", firstText(scene.get("productShotType"), scene.get("shotType"), scene.get("shot_type"), "hero product shot"));
        productShotPlan.put("imagePrompt", imagePrompt);
        productShotPlan.put("cgiFramePrompt", imagePrompt);
        productShotPlan.put("motionPrompt", motionPrompt);
        productShotPlan.put("storyBeat", productStoryBeat);
        productShotPlan.put("productCreativeEvidence", productCreativeEvidence);
        productShotPlan.put("previousShotImageBrief", previousShotBrief);
        productShotPlan.put("nextShotImagePrompt", nextShotBrief);
        productShotPlan.put("sourceProductReferencePolicy", "Use the original product image only for exact identity, packaging, logo, label, color, material, and proportion.");
        productShotPlan.put("generatedFramePolicy", "Generate and approve one shot-specific CGI frame before video generation.");
        productShotPlan.put("seedanceInputPolicy", "@Image1 is the approved CGI shot frame. @Image2 and later images are canonical product references.");
        productShotPlan.put("noHumans", true);

        scene.put("productLed", true);
        scene.put("productCgiScene", true);
        scene.put("noHumans", true);
        scene.put("productShotPlan", productShotPlan);
        scene.put("productStoryBeat", productStoryBeat);
        scene.put("productCreativeEvidence", productCreativeEvidence);
        scene.put("productImagePrompt", imagePrompt);
        scene.put("productionImagePrompt", imagePrompt);
        scene.put("imagePrompt", imagePrompt);
        scene.put("nextProductImagePrompt", nextShotBrief);
        scene.put("productMotionPrompt", motionPrompt);
        scene.put("videoMotionPrompt", motionPrompt);
        scene.put("animationPrompt", motionPrompt);
        scene.put("videoPrompt", motionPrompt);
        scene.put("seedancePrompt", motionPrompt);
        scene.put("negativePrompt", negativePrompt);
        return scene;
    }

    private boolean isNoHumanProductCgiFlow(
            Map<String, Object> scene,
            Map<String, Object> scriptPayload,
            Map<String, Object> request
    ) {
        Map<String, Object> safeScript = scriptPayload == null ? Map.of() : scriptPayload;
        Map<String, Object> safeRequest = request == null ? Map.of() : request;
        Map<String, Object> videoFinishingPlan = firstMap(
                safeRequest.get("videoFinishingPlan"),
                safeScript.get("videoFinishingPlan")
        );
        Map<String, Object> imageLedAdPlan = firstMap(
                safeRequest.get("imageLedAdPlan"),
                safeScript.get("imageLedAdPlan"),
                videoFinishingPlan.get("imageLedAdPlan")
        );
        Map<String, Object> creatorContext = firstMap(safeScript.get("creatorContext"));
        boolean productLed = booleanValue(firstValue(
                scene == null ? null : scene.get("productLed"),
                safeRequest.get("productLed"),
                safeRequest.get("imageLedAdMode"),
                videoFinishingPlan.get("imageLedAdMode"),
                imageLedAdPlan.get("enabled")
        ), false)
                || !firstMap(safeScript.get("productIntelligence"), safeScript.get("productIntelligenceBrief")).isEmpty()
                || !firstMap(creatorContext.get("productIntelligence"), creatorContext.get("productIntelligenceBrief")).isEmpty();
        boolean noHumans = booleanValue(firstValue(
                scene == null ? null : scene.get("noHumans"),
                scene == null ? null : scene.get("no_humans"),
                safeRequest.get("noHumans"),
                safeScript.get("noHumans"),
                firstMap(safeScript.get("productIntelligenceBrief")).get("noHumans"),
                firstMap(creatorContext.get("productIntelligenceBrief")).get("noHumans")
        ), false);
        return productLed && noHumans;
    }

    private String productCgiImagePrompt(
            Map<String, Object> scene,
            String productName,
            Map<String, Object> productCreativeEvidence,
            String productStoryBeat,
            String referenceImageDetails,
            String previousShotBrief,
            String nextShotBrief
    ) {
        String existingPrompt = firstText(
                scene.get("productImagePrompt"),
                scene.get("productionImagePrompt"),
                scene.get("imagePrompt"),
                scene.get("storyboardImagePrompt"),
                scene.get("visualPrompt")
        );
        String shotType = firstText(scene.get("productShotType"), scene.get("shotType"), scene.get("shot_type"), "hero product shot");
        String visualAction = firstText(scene.get("action"), scene.get("visual"), scene.get("description"), scene.get("purpose"));
        String narrativePurpose = firstText(scene.get("retentionGoal"), scene.get("purpose"), scene.get("narrativeBeat"), "Keep the product readable while advancing the ad.");
        String camera = firstText(scene.get("cameraAngle"), scene.get("cameraMovement"), scene.get("cameraMove"), "commercial product camera");
        String lens = firstText(scene.get("lensSuggestion"), scene.get("lens"), "premium product lens with controlled depth of field");
        String lighting = firstText(scene.get("lighting"), scene.get("lightingSetup"), "physically plausible premium commercial lighting");
        String environment = firstText(scene.get("environment"), scene.get("setDesign"), scene.get("background"), "purpose-built CGI product environment");
        String textSpace = firstText(scene.get("textOverlay"), scene.get("caption"), scene.get("captionText"));
        return """
                Create one final, photoreal CGI product-ad frame for %s. This is a finished commercial image, not a storyboard drawing, panel, contact sheet, mood board, or production diagram.
                Use the supplied original product image as the canonical identity reference. Preserve exact package silhouette, geometry, logo placement, label layout, colors, materials, proportions, cap/lid details, and every visible brand feature. Do not redesign or beautify the packaging.
                Shot type: %s.
                Shot-specific visual: %s.
                Existing creative direction: %s.
                Product story beat: %s.
                Grounded product facts and approved creative evidence: %s.
                If this shot visualizes ingredients, materials, features, or benefits, use only facts present in that evidence. Show the named ingredient/material or an honest abstract sensory metaphor; never invent an ingredient, mechanism, certification, result, or claim.
                Narrative and retention purpose: %s.
                Composition and camera: %s. Lens treatment: %s.
                Lighting: %s. Environment/background: %s.
                Render physically plausible materials, reflections, refraction, particles, liquid/texture behavior, contact shadows, and premium macro detail. Keep the product as the unmistakable focal subject with clean mobile-safe framing.
                Previous-frame continuity: %s.
                Next-frame continuity: %s.
                Text-safe area: %s. Reserve space only; do not render captions or invent copy.
                Product reference notes: %s.
                Hard exclusion: no humans, faces, hands, arms, bodies, silhouettes, crowds, presenters, or human reflections. No duplicate product, package mutation, logo drift, label change, invented claim, unreadable text, watermark, or storyboard annotation.
                """.formatted(
                productName,
                shotType,
                firstText(visualAction, "Show the product in a premium, shot-specific CGI composition."),
                firstText(existingPrompt, "Follow the screenplay's approved visual direction."),
                productStoryBeat,
                toJson(productCreativeEvidence),
                narrativePurpose,
                camera,
                lens,
                lighting,
                environment,
                firstText(previousShotBrief, "Opening frame; establish the product identity clearly."),
                firstText(nextShotBrief, "Finish with a clean composition that can cut into the following product shot."),
                firstText(textSpace, "Keep optional copy space clear of the product and platform UI."),
                firstText(referenceImageDetails, "Match the supplied original product image exactly.")
        ).trim();
    }

    private String productCgiMotionPrompt(
            Map<String, Object> scene,
            String productName,
            Map<String, Object> productCreativeEvidence,
            String productStoryBeat,
            String nextShotBrief
    ) {
        Map<String, Object> videoDirectorPlan = firstMap(
                scene.get("videoDirectorPlan"),
                scene.get("video_director_plan"),
                scene.get("directorPlan"),
                scene.get("director_plan")
        );
        String existingMotion = firstText(
                videoDirectorPlan.get("generationPrompt"),
                scene.get("videoMotionPrompt"),
                scene.get("videoPrompt"),
                scene.get("animationPrompt"),
                scene.get("seedancePrompt"),
                scene.get("providerPrompt"),
                scene.get("action")
        );
        String cameraMovement = firstText(
                scene.get("cameraMovement"),
                scene.get("cameraMove"),
                scene.get("motion"),
                "Use restrained, premium product-camera motion."
        );
        String duration = stringValue(firstValue(scene.get("durationSeconds"), scene.get("duration")), "5");
        String hook = firstText(scene.get("hook"), scene.get("openingHook"), scene.get("hookLine"));
        String retention = firstText(scene.get("retentionGoal"), scene.get("retention_goal"));
        String patternInterrupt = firstText(scene.get("patternInterrupt"), scene.get("pattern_interrupt"));
        String brollRole = firstText(scene.get("brollStyle"), scene.get("brollRole"), scene.get("shotPurpose"));
        String lighting = firstText(scene.get("lighting"), scene.get("lightingSetup"));
        String environment = firstText(scene.get("environment"), scene.get("background"), scene.get("setDesign"));
        String editDirection = firstText(
                scene.get("editingNotes"),
                scene.get("editNotes"),
                scene.get("transition"),
                scene.get("cutDirection")
        );
        String soundDirection = firstText(
                scene.get("audioDescription"),
                scene.get("soundPrompt"),
                scene.get("soundDescription"),
                scene.get("soundDesign"),
                scene.get("syncHitDescription"),
                scene.get("ambientBedDescription")
        );
        return """
                Animate the approved CGI frame as a %s-second no-human product commercial shot for %s.
                Product story beat: %s.
                Grounded product evidence: %s.
                Motion and action: %s.
                Camera direction: %s.
                Hook: %s. Retention goal: %s. Pattern interrupt: %s. B-roll role: %s.
                Lighting and set continuity: %s | %s.
                Edit/cut direction: %s.
                Sound-design sync direction: %s.
                Preserve the exact product identity, package geometry, logo, label, colors, materials, scale, and proportions throughout every frame. Use physically plausible motion, stable geometry, premium commercial lighting continuity, natural reflections and shadows, and a clean focal subject.
                Execute at professional commercial-production standard: 4K master minimum, cinema-camera and professional lens intent, controlled exposure and focus, deliberate motion cadence, DP/gaffer-designed lighting with motivated key, shaped fill or negative fill, rim separation and reflection control, and director-level product choreography. Never interpret this as a rookie, casual, phone-camera, or automatic lighting setup.
                Visualize only ingredients, materials, features, benefits, and claims supported by the grounded product evidence. Do not infer or invent product facts.
                End-state continuity: %s.
                Do not introduce people, faces, hands, arms, bodies, human reflections, extra products, package mutations, logo drift, label changes, invented text, random captions, or watermarks.
                """.formatted(
                duration,
                productName,
                productStoryBeat,
                toJson(productCreativeEvidence),
                firstText(existingMotion, "Create subtle product and environmental motion that supports the shot purpose."),
                cameraMovement,
                firstText(hook, "Use the opening composition or movement as the visual hook."),
                firstText(retention, "Deliver one new visual proof or payoff in this shot."),
                firstText(patternInterrupt, "Use a purposeful change in scale, movement, texture, or lighting when the screenplay calls for it."),
                firstText(brollRole, "Product-first CGI proof shot."),
                firstText(lighting, "Match the approved CGI frame's commercial lighting."),
                firstText(environment, "Keep the approved CGI frame's background and set geometry."),
                firstText(editDirection, "Enter and exit on clean motion beats suitable for the planned cut."),
                firstText(soundDirection, "Time product foley, impact, whoosh, or ambience to visible motion without overpowering voiceover."),
                firstText(nextShotBrief, "Settle on a clean transition-ready product composition.")
        ).trim();
    }

    private Map<String, Object> productCreativeEvidence(
            Map<String, Object> productBrief,
            Map<String, Object> productUnderstanding,
            Map<String, Object> scriptPayload,
            Map<String, Object> creatorContext,
            Map<String, Object> request
    ) {
        Map<String, Object> brief = productBrief == null ? Map.of() : productBrief;
        Map<String, Object> understanding = productUnderstanding == null ? Map.of() : productUnderstanding;
        Map<String, Object> script = scriptPayload == null ? Map.of() : scriptPayload;
        Map<String, Object> context = creatorContext == null ? Map.of() : creatorContext;
        Map<String, Object> safeRequest = request == null ? Map.of() : request;
        Map<String, Object> campaignAngle = firstMap(
                safeRequest.get("campaignAngle"),
                script.get("campaignAngle"),
                brief.get("campaignAngle"),
                context.get("campaignAngle")
        );

        Map<String, Object> evidence = new LinkedHashMap<>();
        putProductEvidence(evidence, "category",
                understanding.get("productCategory"), understanding.get("category"),
                brief.get("productCategory"), brief.get("category"));
        putProductEvidence(evidence, "description",
                understanding.get("description"), brief.get("description"), brief.get("summary"),
                script.get("productDescription"), safeRequest.get("productDescription"));
        putProductEvidence(evidence, "ingredientsOrMaterials",
                understanding.get("ingredients"), understanding.get("ingredientList"), understanding.get("materials"),
                brief.get("ingredients"), brief.get("ingredientList"), brief.get("materials"),
                // script.get("ingredients") was the only key ever checked here, but
                // IdeaService.putProductReferencePersistence only ever persists "ingredientDetails"
                // onto scriptPayload - so this evidence silently missed the one field reliably
                // populated for a real product-ad script until now.
                script.get("ingredients"), script.get("ingredientDetails"), safeRequest.get("ingredients"));
        putProductEvidence(evidence, "features",
                understanding.get("features"), understanding.get("keyFeatures"),
                brief.get("features"), brief.get("keyFeatures"), brief.get("productFeatures"));
        putProductEvidence(evidence, "benefits",
                understanding.get("benefits"), understanding.get("keyBenefits"), understanding.get("approvedBenefits"),
                brief.get("benefits"), brief.get("keyBenefits"), brief.get("approvedBenefits"));
        putProductEvidence(evidence, "usp",
                understanding.get("usp"), understanding.get("valueProposition"),
                brief.get("usp"), brief.get("valueProposition"));
        putProductEvidence(evidence, "approvedClaims",
                understanding.get("approvedClaims"), brief.get("approvedClaims"),
                brief.get("requiredMentions"), script.get("approvedClaims"), safeRequest.get("approvedClaims"));
        putProductEvidence(evidence, "packaging",
                understanding.get("packaging"), understanding.get("packagingDescription"),
                brief.get("packaging"), brief.get("packagingDescription"));
        putProductEvidence(evidence, "targetAudience",
                safeRequest.get("targetAudience"), script.get("targetAudience"),
                understanding.get("targetAudience"), brief.get("targetAudience"),
                context.get("targetAudience"), firstMap(script.get("brandContext")).get("targetAudience"),
                firstMap(context.get("brandContext")).get("targetAudience"));
        putProductEvidence(evidence, "campaignObjective",
                safeRequest.get("campaignObjective"), script.get("campaignObjective"),
                brief.get("campaignObjective"), context.get("campaignObjective"),
                firstMap(script.get("brandContext")).get("campaignObjective"),
                firstMap(context.get("brandContext")).get("campaignObjective"));
        putProductEvidence(evidence, "campaignAngle",
                campaignAngle.get("description"), campaignAngle.get("title"), campaignAngle);
        putProductEvidence(evidence, "campaignNotes",
                safeRequest.get("campaignNotes"), brief.get("campaignNotes"),
                script.get("campaignNotes"), context.get("campaignNotes"));
        putProductEvidence(evidence, "formatPlaybook",
                safeRequest.get("formatPlaybook"), brief.get("formatPlaybook"),
                firstMap(brief.get("creativeDirection")).get("formatPlaybook"));
        putProductEvidence(evidence, "evidencePolicy",
                understanding.get("evidencePolicy"), brief.get("evidencePolicy"),
                "Use only supplied or researched product facts. Never invent ingredients, benefits, claims, certifications, pricing, or results.");
        return evidence;
    }

    private void putProductEvidence(
            Map<String, Object> evidence,
            String key,
            Object... candidates
    ) {
        if (evidence == null || key == null || key.isBlank() || candidates == null) {
            return;
        }
        for (Object candidate : candidates) {
            if (candidate == null
                    || candidate instanceof CharSequence text && text.toString().isBlank()
                    || candidate instanceof Collection<?> collection && collection.isEmpty()
                    || candidate instanceof Map<?, ?> map && map.isEmpty()) {
                continue;
            }
            evidence.put(key, candidate);
            return;
        }
    }

    private String productShotImageBrief(List<Map<String, Object>> scenes, int index) {
        if (scenes == null || index < 0 || index >= scenes.size()) {
            return "";
        }
        Map<String, Object> adjacent = scenes.get(index);
        return String.join(" | ", List.of(
                "Shot " + intValue(firstValue(adjacent.get("shotNumber"), adjacent.get("sceneNumber")), index + 1),
                firstText(adjacent.get("shotType"), adjacent.get("shot_type"), "product shot"),
                firstText(adjacent.get("action"), adjacent.get("visual"), adjacent.get("description"), adjacent.get("purpose"), "product continuity frame"),
                firstText(adjacent.get("cameraAngle"), adjacent.get("cameraMovement"), adjacent.get("cameraMove"), "consistent camera language"),
                firstText(adjacent.get("lighting"), adjacent.get("environment"), "consistent lighting and set")
        ));
    }

    private String appendPromptClause(String base, String clause) {
        String left = firstText(base);
        String right = firstText(clause);
        if (left.isBlank()) {
            return right;
        }
        if (right.isBlank() || left.toLowerCase(Locale.ROOT).contains(right.toLowerCase(Locale.ROOT))) {
            return left;
        }
        return left + ", " + right;
    }
}
