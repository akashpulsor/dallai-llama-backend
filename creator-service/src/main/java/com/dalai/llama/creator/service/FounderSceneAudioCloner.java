package com.dalai.llama.creator.service;

import com.dalai.llama.creator.domain.entity.CreatorScript;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static com.dalai.llama.creator.service.screenplayvideo.MapCoercion.*;

/**
 * Extraction of what used to be ScreenplayVideoService.prepareFounderSceneAudio() /
 * matchesPersistedSceneDialogueAudio() - the "clone or reuse this scene's founder dialogue audio"
 * decision, called from both generateSceneDialogueVoice() (allowGeneration=true, the explicit
 * clone-this-scene flow) and the avatar scene-generation path (allowGeneration=false, reusing an
 * already-accepted clone) - a confirmed real cross-flow dependency, not duplicated.
 *
 * <p>Same package as ScreenplayVideoService (not the screenplayvideo subpackage), deliberately,
 * following the ProviderRequestBuilder precedent: this needs founderAvatarProfile,
 * dialogueTextForScene, generationModeFor, providerForSceneGeneration and half a dozen other
 * domain-specific collaborators that are used throughout ScreenplayVideoService for unrelated
 * things too, so they stay there widened to package-private rather than duplicated. Only the two
 * genuinely Spring-bean dependencies (CreatorAiService, GoogleChirpVoiceGenerationService) are
 * taken directly instead of reached through owner.
 *
 * <p>This is a narrower slice than the DialogueVoiceCloner interface sketched in the refactor plan
 * artifact - that interface assumed generateSceneDialogueVoice/decideSceneDialogueVoice/
 * combineSceneDialogueAudio could move wholesale behind a clean Spring-injected interface like
 * ShotPlanTagGateway/AvatarDialogueSyncGateway/SceneChatEditor did. Tracing the call graph end to
 * end showed otherwise: those three methods also call founderAvatarProfile, localizeDialogueScenes,
 * hydrateVideoRunForResponse, synchronizeAvatarDialogueSources - shared "run domain" primitives used
 * throughout the class, not exclusive to voice cloning, and not yet extracted themselves (that's the
 * still-pending InitialRunAssemblyService/VideoRunHydrationService rows in the plan's Phase B table).
 * This class extracts only the piece that genuinely is self-contained: the actual clone-or-reuse
 * decision and voice-provider call.
 */
final class FounderSceneAudioCloner {

    private static final Logger log = LoggerFactory.getLogger(FounderSceneAudioCloner.class);

    private final ScreenplayVideoService owner;
    private final CreatorAiService creatorAiService;
    private final GoogleChirpVoiceGenerationService voiceGenerationService;

    FounderSceneAudioCloner(
            ScreenplayVideoService owner,
            CreatorAiService creatorAiService,
            GoogleChirpVoiceGenerationService voiceGenerationService
    ) {
        this.owner = owner;
        this.creatorAiService = creatorAiService;
        this.voiceGenerationService = voiceGenerationService;
    }

    Map<String, Object> prepareFounderSceneAudio(
            CreatorScript script,
            UUID runId,
            UUID jobId,
            Map<String, Object> run,
            Map<String, Object> scene,
            boolean allowGeneration
    ) {
        if (!"talking_head".equals(owner.generationModeFor(scene, run, firstMap(scene.get("providerRequest"))))) {
            return Map.of();
        }
        Map<String, Object> providerRequest = firstMap(scene.get("providerRequest"));
        if (!"dalai_llama".equals(owner.providerForSceneGeneration(scene, run, providerRequest))) {
            return Map.of();
        }
        Map<String, Object> founderProfile = owner.founderAvatarProfile(
                providerRequest,
                run,
                copyMap(script.getScriptPayload())
        );
        Map<String, Object> localModels = firstMap(
                providerRequest.get("localModels"),
                founderProfile.get("localModels")
        );
        String voiceModel = owner.requireSceneVoiceMethod(firstText(
                providerRequest.get("voiceModel"),
                providerRequest.get("voiceCloneMethod"),
                localModels.get("voiceModel"),
                scene.get("dialogueCloneVoiceModel"),
                firstMap(founderProfile.get("localModels")).get("voiceModel")
        ));
        String talkingAvatarModel = owner.normalizeLocalTalkingAvatarModel(firstText(
                providerRequest.get("talkingAvatarModel"),
                localModels.get("talkingAvatarModel")
        ));
        if (!booleanValue(founderProfile.get("consentConfirmed"), false)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Confirm creator consent before cloning or using scene dialogue."
            );
        }
        if (!allowGeneration
                && !"fal_heygen_avatar4".equals(talkingAvatarModel)
                && !"APPROVED".equalsIgnoreCase(firstText(founderProfile.get("avatarPreviewStatus")))) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Create and approve the portrait avatar quality test before generating avatar scenes."
            );
        }
        String dialogue = owner.dialogueTextForScene(scene);
        if (dialogue.isBlank()) {
            return Map.of();
        }
        String language = firstText(
                providerRequest.get("language"),
                founderProfile.get("language"),
                run.get("dialogueLanguage"),
                "English"
        );
        String languageCode = firstText(
                providerRequest.get("languageCode"),
                founderProfile.get("languageCode"),
                run.get("languageCode"),
                owner.languageCodeFor(language)
        );
        if ("client_rvc_english".equals(voiceModel) && !owner.sameLanguage(language, "English")) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Prepare the current dialogue in English before generating the client avatar voice."
            );
        }
        if ("uploaded_founder_audio".equals(voiceModel)) {
            Map<String, Object> exactAudioAsset = firstMap(
                    founderProfile.get("exactFounderAudioAsset"),
                    founderProfile.get("finalFounderAudioAsset")
            );
            if (!exactAudioAsset.isEmpty() && mapListValue(run.get("scenes")).size() == 1) {
                return exactAudioAsset;
            }
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Full-video avatar scenes need a reusable cloned voice. Exact uploaded audio can only be used when the screenplay has one avatar scene."
            );
        }
        if ("synthesia_managed".equals(voiceModel)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Select a DalaiLlama cloned voice before using the DalaiLlama portrait avatar."
            );
        }

        String profileId = firstText(
                providerRequest.get("voiceProfileId"),
                founderProfile.get("voiceProfileId"),
                localModels.get("voiceProfileId"),
                "client_rvc_english".equals(voiceModel) ? "founder_female_v1" : null
        );
        String providerVoiceId = owner.providerVoiceIdForMethod(voiceModel, founderProfile);
        String dialogueFingerprint = owner.stableFingerprint(
                dialogue,
                voiceModel,
                profileId,
                providerVoiceId,
                language,
                languageCode,
                firstText(founderProfile.get("pronunciationGuide"))
        );
        Map<String, Object> existingAsset = firstMap(scene.get("dialogueAudio"));
        String storedDialogueFingerprint = firstText(
                existingAsset.get("dialogueFingerprint"),
                firstMap(existingAsset.get("metadata")).get("dialogueFingerprint"),
                scene.get("dialogueCloneFingerprint")
        );
        boolean matchingDialogueClone = matchesPersistedSceneDialogueAudio(
                scene,
                existingAsset,
                dialogueFingerprint,
                dialogue,
                language,
                voiceModel
        );
        if (matchingDialogueClone
                && (allowGeneration || "APPROVED".equalsIgnoreCase(firstText(scene.get("dialogueCloneStatus"))))) {
            log.info(
                    "Reusing persisted scene dialogue audio runId={} sceneId={} language={} voiceModel={} match={}",
                    runId,
                    firstText(scene.get("id"), scene.get("sceneId")),
                    language,
                    voiceModel,
                    dialogueFingerprint.equals(storedDialogueFingerprint) ? "fingerprint" : "accepted_fields"
            );
            return existingAsset;
        }
        if (!allowGeneration) {
            if (matchingDialogueClone) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Accept the cloned dialogue for this scene before creating its avatar."
                );
            }
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Clone this scene dialogue in the selected language before creating its avatar."
            );
        }

        Map<String, Object> voiceOptions = new LinkedHashMap<>();
        voiceOptions.put("provider", "dalai_llama");
        voiceOptions.put("voiceModel", voiceModel);
        voiceOptions.put("voiceProfileId", profileId);
        voiceOptions.put("localModels", localModels);
        voiceOptions.put("founderAvatarProfile", founderProfile);
        voiceOptions.put("founderKit", founderProfile);
        voiceOptions.put("spokenText", owner.applyPronunciationGuide(dialogue, firstText(founderProfile.get("pronunciationGuide"))));
        voiceOptions.put("captionText", dialogue);
        voiceOptions.put("pronunciationGuide", firstText(founderProfile.get("pronunciationGuide")));
        voiceOptions.put("language", language);
        voiceOptions.put("dialogueLanguage", language);
        voiceOptions.put("languageCode", languageCode);
        voiceOptions.put("dialogueLanguageCode", languageCode);
        voiceOptions.put("voiceLanguage", firstText(founderProfile.get("voiceLanguage"), language));
        voiceOptions.put("voiceLanguageCode", firstText(founderProfile.get("voiceLanguageCode"), languageCode));
        voiceOptions.put("languageBoost", firstText(founderProfile.get("minimaxLanguageBoost"), founderProfile.get("languageBoost"), "auto"));
        voiceOptions.put("referenceLanguage", firstText(founderProfile.get("referenceLanguage")));
        voiceOptions.put("minimaxVoiceId", firstText(founderProfile.get("minimaxVoiceId")));
        voiceOptions.put("elevenLabsVoiceId", firstText(founderProfile.get("elevenLabsVoiceId")));
        voiceOptions.put("sarvamVoiceId", firstText(founderProfile.get("sarvamVoiceId")));
        voiceOptions.put("providerVoiceId", providerVoiceId);
        voiceOptions.put("consentConfirmed", booleanValue(founderProfile.get("consentConfirmed"), false));
        voiceOptions.put(
                "requestId",
                "scene-voice-" + runId + "-" + firstText(scene.get("id"), scene.get("sceneId"), "scene")
                        + "-" + dialogueFingerprint.substring(0, Math.min(12, dialogueFingerprint.length()))
        );

        CreatorAiService.AiUsageContext usageContext = new CreatorAiService.AiUsageContext(
                script.getTenantId(),
                script.getUserId(),
                script.getProjectId(),
                jobId,
                null
        );
        creatorAiService.assertWalletBalanceForModelRun("SCREENPLAY_AUDIO_VOICE_GENERATE", usageContext);
        GoogleChirpVoiceGenerationService.GeneratedVoice voice = voiceGenerationService.generateVoice(
                firstText(voiceOptions.get("spokenText"), dialogue),
                voiceOptions
        );
        Map<String, Object> voiceMetadata = new LinkedHashMap<>(voice.metadata());
        voiceMetadata.put("sceneId", firstText(scene.get("id"), scene.get("sceneId")));
        voiceMetadata.put("sceneNumber", intValue(firstValue(scene.get("sceneNumber"), scene.get("shotNumber")), 1));
        voiceMetadata.put("dialogueFingerprint", dialogueFingerprint);
        voiceMetadata.put("voiceProfileId", profileId);
        voiceMetadata.put("voiceModel", voiceModel);
        voiceMetadata.put("language", language);
        voiceMetadata.put("languageCode", languageCode);
        voiceMetadata.put("dialogueSource", "localized_screenplay_scene");
        Map<String, Object> asset = owner.storeAudioAsset(
                script,
                runId,
                voice.bytes(),
                voice.contentType(),
                "founder_scene_voice",
                voiceMetadata,
                voice.providerRequest(),
                voice.providerResponse()
        );
        creatorAiService.publishProviderUsageDebit(
                "SCREENPLAY_AUDIO_VOICE_GENERATE",
                firstText(voiceMetadata.get("provider"), "dalai_llama"),
                firstText(voiceMetadata.get("model"), voiceModel),
                firstMap(voiceMetadata.get("costMetadata")),
                usageContext,
                "Generated approved cloned voice for avatar scene"
        );
        return asset;
    }

    private boolean matchesPersistedSceneDialogueAudio(
            Map<String, Object> scene,
            Map<String, Object> existingAsset,
            String expectedFingerprint,
            String dialogue,
            String language,
            String voiceModel
    ) {
        String storedFingerprint = firstText(
                existingAsset.get("dialogueFingerprint"),
                firstMap(existingAsset.get("metadata")).get("dialogueFingerprint"),
                scene.get("dialogueCloneFingerprint")
        );
        boolean hasStoredAudio = !firstText(
                existingAsset.get("objectKey"),
                existingAsset.get("assetUrl"),
                existingAsset.get("signedUrl")
        ).isBlank();
        boolean acceptedFieldsMatch = ScreenplayVideoService.normalizeDialogueText(dialogue)
                .equals(ScreenplayVideoService.normalizeDialogueText(firstText(scene.get("dialogueCloneText"))))
                && owner.sameLanguage(language, firstText(scene.get("dialogueCloneLanguage")))
                && voiceModel.equals(firstText(scene.get("dialogueCloneVoiceModel"), scene.get("dialogueCloneMethod")));
        return hasStoredAudio
                && (expectedFingerprint.equals(storedFingerprint) || acceptedFieldsMatch);
    }
}
