package com.dalai.llama.creator.service;

import com.dalai.llama.creator.domain.entity.CreatorAvatarSceneDialogue;
import com.dalai.llama.creator.domain.entity.CreatorGenerationJob;
import com.dalai.llama.creator.domain.entity.CreatorScript;
import com.dalai.llama.creator.repository.CreatorGenerationJobRepository;
import com.dalai.llama.creator.service.screenplayvideo.AvatarDialogueSyncGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static com.dalai.llama.creator.service.screenplayvideo.MapCoercion.*;

/**
 * Extraction of the three public dialogue-voice-cloning endpoints that used to live directly on
 * ScreenplayVideoService: generateSceneDialogueVoice, decideSceneDialogueVoice,
 * combineSceneDialogueAudio - the last piece of the stage-2 LLD's interface 05.
 *
 * <p>Not a screenplayvideo/ interface, for the same reason FounderSceneAudioCloner and
 * InitialRunAssembler aren't: these three methods lean on founderAvatarProfile,
 * localizeDialogueScenes, hydrateVideoRunForResponse, synchronizeAvatarDialogueSources and a dozen
 * other domain-specific collaborators used throughout ScreenplayVideoService for unrelated things
 * too. Same package, owner back-reference, ProviderRequestBuilder shape.
 *
 * <p>prepareFounderSceneAudio (FounderSceneAudioCloner's entry point) is reached through owner
 * rather than duplicated here - it's also called from ScreenplayVideoService's own avatar
 * scene-generation path (runRegenerateSceneJob-adjacent code) to reuse an already-cloned voice, a
 * confirmed real cross-flow dependency kept explicit rather than duplicated.
 */
final class DialogueVoiceCloner {

    private static final Logger log = LoggerFactory.getLogger(DialogueVoiceCloner.class);

    private static final String JOB_SCREENPLAY_VIDEO_SCENE_VOICE = "SCREENPLAY_VIDEO_SCENE_VOICE";
    private static final String JOB_SCREENPLAY_VIDEO_SCENE_VOICE_APPROVAL = "SCREENPLAY_VIDEO_SCENE_VOICE_APPROVAL";
    private static final String JOB_SCREENPLAY_VIDEO_DIALOGUE_COMBINE = "SCREENPLAY_VIDEO_DIALOGUE_COMBINE";

    private final ScreenplayVideoService owner;
    private final CreatorGenerationJobRepository generationJobRepository;
    private final GenerationJobService generationJobService;
    private final AvatarDialogueSyncGateway avatarDialogueSyncGateway;
    private final AvatarSceneDialogueService avatarSceneDialogueService;
    private final CreatorAiService creatorAiService;
    private final AssetStorageService assetStorageService;

    DialogueVoiceCloner(
            ScreenplayVideoService owner,
            CreatorGenerationJobRepository generationJobRepository,
            GenerationJobService generationJobService,
            AvatarDialogueSyncGateway avatarDialogueSyncGateway,
            AvatarSceneDialogueService avatarSceneDialogueService,
            CreatorAiService creatorAiService,
            AssetStorageService assetStorageService
    ) {
        this.owner = owner;
        this.generationJobRepository = generationJobRepository;
        this.generationJobService = generationJobService;
        this.avatarDialogueSyncGateway = avatarDialogueSyncGateway;
        this.avatarSceneDialogueService = avatarSceneDialogueService;
        this.creatorAiService = creatorAiService;
        this.assetStorageService = assetStorageService;
    }

    Map<String, Object> generateSceneDialogueVoice(
            UUID runId,
            String sceneId,
            Map<String, Object> request,
            String tenantId,
            String userId
    ) {
        String safeTenantId = defaultString(tenantId, "unknown");
        String safeUserId = defaultString(userId, "anonymous");
        boolean cloneLockAcquired = generationJobRepository.tryAcquireTransactionalAdvisoryLock(
                "screenplay-scene-voice:" + safeTenantId + ":" + safeUserId + ":" + runId
        );
        if (!cloneLockAcquired) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Wait for the current scene voice cloning process to complete."
            );
        }
        ScreenplayVideoService.RunRecord record = owner.loadRun(runId, safeTenantId, safeUserId);
        Map<String, Object> run = copyMap(record.run());
        CreatorScript script = owner.loadScript(uuidValue(run.get("scriptId")), safeTenantId, safeUserId);
        owner.synchronizeAvatarDialogueSources(script, run);
        List<Map<String, Object>> scenes = mapListValue(run.get("scenes"));
        int sceneIndex = owner.findSceneIndex(scenes, sceneId);
        Map<String, Object> scene = copyMap(scenes.get(sceneIndex));
        int sceneNumber = positiveInt(firstValue(scene.get("sceneNumber"), scene.get("shotNumber")), sceneIndex + 1);
        CreatorAvatarSceneDialogue sourceDialogueRecord = avatarDialogueSyncGateway.currentSource(
                script,
                runId,
                sceneNumber
        );
        if (sourceDialogueRecord != null) {
            avatarDialogueSyncGateway.applyRecord(scene, sourceDialogueRecord, sourceDialogueRecord);
        }
        Map<String, Object> input = copyMap(request);
        input.put("runId", runId.toString());
        input.put("scriptId", script.getId().toString());
        input.put("sceneId", sceneId);
        input.put("generationMode", "talking_head");
        input.put("provider", "dalai_llama");

        CreatorGenerationJob job = generationJobService.startGenerationJob(
                JOB_SCREENPLAY_VIDEO_SCENE_VOICE,
                safeTenantId,
                safeUserId,
                script.getProjectId(),
                input
        );
        try {
            String sourceDialogue = owner.dialogueTextForScene(scene);
            if (sourceDialogue.isBlank()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "This scene has no spoken dialogue to clone.");
            }
            Map<String, Object> founderProfile = new LinkedHashMap<>(owner.founderAvatarProfile(
                    input,
                    run,
                    firstMap(run.get("creatorContext"), script.getScriptPayload())
            ));
            String selectedVoiceMethod = owner.requireSceneVoiceMethod(firstText(
                    input.get("voiceModel"),
                    input.get("localVoiceModel"),
                    input.get("voiceCloneMethod"),
                    firstMap(input.get("localModels"), input.get("localAvatarModels")).get("voiceModel"),
                    firstMap(founderProfile.get("localModels")).get("voiceModel")
            ));
            if (!booleanValue(founderProfile.get("consentConfirmed"), false)) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Confirm creator consent before cloning scene dialogue."
                );
            }
            String sourceLanguage = firstText(
                    sourceDialogueRecord == null ? null : sourceDialogueRecord.getLanguage(),
                    scene.get("dialogueLanguage"),
                    scene.get("sourceDialogueLanguage"),
                    run.get("dialogueLanguage"),
                    script.getDialogueLanguage(),
                    firstMap(script.getScriptPayload()).get("dialogueLanguage"),
                    "English"
            );
            String targetLanguage = firstText(
                    input.get("dialogueLanguage"),
                    input.get("language"),
                    sourceLanguage
            );
            String targetLanguageCode = firstText(
                    input.get("languageCode"),
                    owner.languageCodeFor(targetLanguage)
            );
            if ("client_rvc_english".equals(selectedVoiceMethod) && !owner.sameLanguage(targetLanguage, "English")) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "The trained client voice supports English only. Choose English or another voice clone method."
                );
            }
            boolean translationRequired = !owner.sameLanguage(sourceLanguage, targetLanguage);
            UUID requestedDialogueId = uuidValue(firstValue(
                    input.get("dialogueRecordId"),
                    input.get("avatarDialogueId"),
                    input.get("selectedDialogueId")
            ));
            CreatorAvatarSceneDialogue selectedDialogueRecord = sourceDialogueRecord;
            if (translationRequired && sourceDialogueRecord != null && avatarSceneDialogueService != null) {
                selectedDialogueRecord = avatarSceneDialogueService.resolveCurrentVariant(
                        sourceDialogueRecord.getRootDialogueId(),
                        requestedDialogueId,
                        targetLanguage
                ).orElse(null);
                if (requestedDialogueId != null && selectedDialogueRecord == null) {
                    throw new ResponseStatusException(
                            HttpStatus.CONFLICT,
                            "The selected dialogue translation is no longer current. Refresh the video workspace and choose it again."
                    );
                }
            }
            if (translationRequired && selectedDialogueRecord != null) {
                avatarDialogueSyncGateway.applyRecord(scene, sourceDialogueRecord, selectedDialogueRecord);
            } else if (translationRequired) {
                scene = copyMap(owner.localizeDialogueScenes(
                        script,
                        List.of(scene),
                        sourceLanguage,
                        targetLanguage,
                        targetLanguageCode,
                        job.getId()
                ).get(0));
                if (sourceDialogueRecord != null && avatarSceneDialogueService != null) {
                    selectedDialogueRecord = avatarSceneDialogueService.saveTranslation(
                            sourceDialogueRecord,
                            targetLanguage,
                            targetLanguageCode,
                            owner.dialogueTextForScene(scene),
                            uuidValue(scene.get("dialogueLocalizationPromptRunId")),
                            job.getId(),
                            creatorAiService.providerName(),
                            creatorAiService.modelName()
                    );
                    avatarDialogueSyncGateway.applyRecord(scene, sourceDialogueRecord, selectedDialogueRecord);
                }
            } else if (sourceDialogueRecord != null) {
                avatarDialogueSyncGateway.applyRecord(scene, sourceDialogueRecord, sourceDialogueRecord);
            }
            String translatedDialogue = owner.dialogueTextForScene(scene);
            if (translatedDialogue.isBlank()) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "Dialogue translation returned no spoken text. Voice cloning was not submitted."
                );
            }
            Map<String, Object> selectedLocalModels = new LinkedHashMap<>(firstMap(
                    input.get("localModels"),
                    input.get("localAvatarModels"),
                    founderProfile.get("localModels")
            ));
            selectedLocalModels.put("voiceModel", selectedVoiceMethod);
            founderProfile.put("localModels", selectedLocalModels);
            input.put("voiceModel", selectedVoiceMethod);
            input.put("localVoiceModel", selectedVoiceMethod);
            input.put("voiceCloneMethod", selectedVoiceMethod);
            input.put("localModels", selectedLocalModels);
            input.put("founderAvatarProfile", founderProfile);
            input.put("founderKit", founderProfile);
            scene.put("generationMode", "talking_head");
            scene.put("dialogueLanguage", targetLanguage);
            scene.put("languageCode", targetLanguageCode);
            scene.put("dialogueLocalizationStatus", translationRequired ? "COMPLETED" : "NOT_REQUIRED");
            scene.put("dialogueTranslationApplied", translationRequired);
            scene.put("dialogueCloneVoiceModel", selectedVoiceMethod);
            scene.put("dialogueCloneMethod", selectedVoiceMethod);
            scene.put("dialogueCloneStatus", "GENERATING");
            scene.put("dialogueCloneAccepted", false);
            scene.remove("dialogueCloneError");
            input.put("dialogueLanguage", targetLanguage);
            input.put("language", targetLanguage);
            input.put("languageCode", targetLanguageCode);
            input.put("dialogueRecordId", selectedDialogueRecord == null ? null : selectedDialogueRecord.getId().toString());
            input.put("rootDialogueId", sourceDialogueRecord == null ? null : sourceDialogueRecord.getRootDialogueId().toString());
            Map<String, Object> providerRequest = owner.buildProviderRequest(run, scene, input);
            providerRequest.put("generationMode", "talking_head");
            providerRequest.put("language", targetLanguage);
            providerRequest.put("languageCode", targetLanguageCode);
            providerRequest.put("voiceModel", selectedVoiceMethod);
            providerRequest.put("voiceCloneMethod", selectedVoiceMethod);
            providerRequest.put("localModels", selectedLocalModels);
            scene.put("providerRequest", providerRequest);
            scene.put("updatedAt", OffsetDateTime.now().toString());
            scenes.set(sceneIndex, scene);
            run.put("scenes", scenes);
            run.put("sceneClips", scenes);
            run.put("updatedAt", OffsetDateTime.now().toString());
            run.put(
                    "message",
                    translationRequired
                            ? "Scene dialogue translated to " + targetLanguage + ". Cloning with " + selectedVoiceMethod + "."
                            : "Cloning scene dialogue with " + selectedVoiceMethod + "."
            );
            generationJobService.updateGenerationJobProgress(
                    job.getId(),
                    translationRequired ? 48 : 30,
                    translationRequired ? "Dialogue translated; cloning selected voice" : "Cloning selected scene voice",
                    owner.outputPayload(run, stringValue(run.get("message"), "Cloning scene dialogue."))
            );

            Map<String, Object> asset = owner.prepareFounderSceneAudio(
                    script,
                    runId,
                    job.getId(),
                    run,
                    scene,
                    true
            );
            if (asset.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "This scene has no spoken dialogue to clone.");
            }
            Map<String, Object> metadata = firstMap(asset.get("metadata"));
            String audioUrl = firstText(asset.get("assetUrl"), asset.get("signedUrl"), asset.get("publicUrl"));
            scene.put("dialogueAudio", asset);
            scene.put("voiceTrack", audioUrl);
            scene.put("dialogueCloneStatus", "PREVIEW_READY");
            scene.put("dialogueCloneAccepted", false);
            scene.put("dialogueCloneText", owner.dialogueTextForScene(scene));
            scene.put("dialogueCloneLanguage", targetLanguage);
            scene.put("dialogueCloneLanguageCode", targetLanguageCode);
            scene.put("dialogueCloneFingerprint", firstText(
                    asset.get("dialogueFingerprint"),
                    metadata.get("dialogueFingerprint")
            ));
            scene.put("dialogueCloneVoiceModel", selectedVoiceMethod);
            scene.put("dialogueCloneMethod", selectedVoiceMethod);
            scene.put("dialogueCloneGeneratedAt", OffsetDateTime.now().toString());
            scene.put("updatedAt", OffsetDateTime.now().toString());
            scenes.set(sceneIndex, scene);
            owner.clearCombinedDialogueAudio(run);

            run.put("scenes", scenes);
            run.put("sceneClips", scenes);
            run.put("updatedAt", OffsetDateTime.now().toString());
            run.put("message", "Cloned dialogue is ready for scene " + firstText(scene.get("sceneNumber"), sceneId) + ".");
            generationJobService.completeGenerationJob(job.getId(), owner.outputPayload(run, "Scene cloned dialogue ready."));

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "PREVIEW_READY");
            response.put("voiceModel", selectedVoiceMethod);
            response.put("dialogueLanguage", targetLanguage);
            response.put("dialogueTranslated", translationRequired);
            response.put("scene", scene);
            response.put("dialogueAudio", asset);
            response.put("videoRun", owner.hydrateVideoRunForResponse(run, safeTenantId, safeUserId));
            return response;
        } catch (RuntimeException ex) {
            String message = defaultString(ex.getMessage(), ex.getClass().getSimpleName());
            scene.put("dialogueCloneStatus", "FAILED");
            scene.put("dialogueCloneAccepted", false);
            scene.put("dialogueCloneError", message);
            scene.put("updatedAt", OffsetDateTime.now().toString());
            scenes.set(sceneIndex, scene);
            run.put("scenes", scenes);
            run.put("sceneClips", scenes);
            run.put("updatedAt", OffsetDateTime.now().toString());
            run.put("message", message);
            generationJobService.failGenerationJob(job.getId(), message, owner.outputPayload(run, message));
            throw ex;
        }
    }

    Map<String, Object> decideSceneDialogueVoice(
            UUID runId,
            String sceneId,
            Map<String, Object> request,
            String tenantId,
            String userId
    ) {
        String safeTenantId = defaultString(tenantId, "unknown");
        String safeUserId = defaultString(userId, "anonymous");
        // Confirmed fix: this method used to take no advisory lock at all, while its two siblings
        // (generateSceneDialogueVoice, combineSceneDialogueAudio) both lock this same key - a real
        // race window against a concurrent generate/combine call on the same scene. Closed by
        // taking the identical lock here too.
        boolean decisionLockAcquired = generationJobRepository.tryAcquireTransactionalAdvisoryLock(
                "screenplay-scene-voice:" + safeTenantId + ":" + safeUserId + ":" + runId
        );
        if (!decisionLockAcquired) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Wait for the current scene voice cloning process to complete."
            );
        }
        ScreenplayVideoService.RunRecord record = owner.loadRun(runId, safeTenantId, safeUserId);
        Map<String, Object> run = copyMap(record.run());
        CreatorScript script = owner.loadScript(uuidValue(run.get("scriptId")), safeTenantId, safeUserId);
        List<Map<String, Object>> scenes = mapListValue(run.get("scenes"));
        int sceneIndex = owner.findSceneIndex(scenes, sceneId);
        Map<String, Object> scene = copyMap(scenes.get(sceneIndex));
        Map<String, Object> dialogueAudio = firstMap(scene.get("dialogueAudio"));
        if (dialogueAudio.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Clone this scene dialogue before accepting it.");
        }
        String decision = firstText(
                request == null ? null : request.get("decision"),
                request == null ? null : request.get("action"),
                "APPROVE"
        ).toUpperCase(Locale.ROOT);
        if (!"APPROVE".equals(decision) && !"REJECT".equals(decision)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Scene dialogue decision must be APPROVE or REJECT.");
        }

        Map<String, Object> input = copyMap(request);
        input.put("runId", runId.toString());
        input.put("scriptId", script.getId().toString());
        input.put("sceneId", sceneId);
        input.put("decision", decision);
        CreatorGenerationJob job = generationJobService.startGenerationJob(
                JOB_SCREENPLAY_VIDEO_SCENE_VOICE_APPROVAL,
                safeTenantId,
                safeUserId,
                script.getProjectId(),
                input
        );

        if ("APPROVE".equals(decision)) {
            scene.put("dialogueCloneStatus", "APPROVED");
            scene.put("dialogueCloneAccepted", true);
            scene.put("dialogueCloneAcceptedAt", OffsetDateTime.now().toString());
            scene.put("dialogueCloneAcceptedBy", safeUserId);
        } else {
            scene.put("dialogueCloneStatus", "REJECTED");
            scene.put("dialogueCloneAccepted", false);
            scene.remove("dialogueCloneAcceptedAt");
            scene.remove("dialogueCloneAcceptedBy");
            scene.remove("dialogueAudio");
            scene.remove("voiceTrack");
            owner.clearCombinedDialogueAudio(run);
        }
        scene.put("updatedAt", OffsetDateTime.now().toString());
        scenes.set(sceneIndex, scene);
        run.put("scenes", scenes);
        run.put("sceneClips", scenes);
        run.put("updatedAt", OffsetDateTime.now().toString());
        run.put("message", "APPROVE".equals(decision)
                ? "Scene cloned dialogue accepted."
                : "Scene cloned dialogue rejected. Generate another clone.");
        generationJobService.completeGenerationJob(job.getId(), owner.outputPayload(run, stringValue(run.get("message"), "")));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", scene.get("dialogueCloneStatus"));
        response.put("scene", scene);
        response.put("videoRun", owner.hydrateVideoRunForResponse(run, safeTenantId, safeUserId));
        return response;
    }

    Map<String, Object> combineSceneDialogueAudio(
            UUID runId,
            String tenantId,
            String userId
    ) {
        String safeTenantId = defaultString(tenantId, "unknown");
        String safeUserId = defaultString(userId, "anonymous");
        boolean combineLockAcquired = generationJobRepository.tryAcquireTransactionalAdvisoryLock(
                "screenplay-scene-voice:" + safeTenantId + ":" + safeUserId + ":" + runId
        );
        if (!combineLockAcquired) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Wait for the current scene voice cloning process to complete."
            );
        }

        ScreenplayVideoService.RunRecord record = owner.loadRun(runId, safeTenantId, safeUserId);
        Map<String, Object> run = copyMap(record.run());
        CreatorScript script = owner.loadScript(uuidValue(run.get("scriptId")), safeTenantId, safeUserId);
        List<Map<String, Object>> scenes = mapListValue(run.get("scenes"));
        List<Map<String, Object>> availableInputs = owner.sceneDialogueAudioInputs(scenes);
        if (availableInputs.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Clone at least one scene dialogue before combining audio."
            );
        }

        String currentFingerprint = owner.sceneDialogueAudioFingerprint(availableInputs, scenes.size());
        Map<String, Object> existingAsset = firstNonEmptyMap(
                run.get("combinedDialogueAudio"),
                run.get("combinedSceneDialogueAudio")
        );
        String existingFingerprint = firstText(
                existingAsset.get("combinedFingerprint"),
                existingAsset.get("dialogueFingerprint"),
                firstMap(existingAsset.get("metadata")).get("dialogueFingerprint")
        );
        if (owner.hasStoredAssetLocation(existingAsset) && currentFingerprint.equals(existingFingerprint)) {
            Map<String, Object> refreshedAsset = owner.refreshAudioAssetReference(existingAsset);
            run.put("combinedDialogueAudio", refreshedAsset);
            run.put("combinedSceneDialogueAudio", refreshedAsset);
            run.put("combinedDialogueTrack", firstText(
                    refreshedAsset.get("assetUrl"),
                    refreshedAsset.get("signedUrl"),
                    refreshedAsset.get("publicUrl")
            ));
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "READY");
            response.put("reused", true);
            response.put("combinedDialogueAudio", refreshedAsset);
            response.put("includedSceneCount", availableInputs.size());
            response.put("totalSceneCount", scenes.size());
            response.put("skippedSceneNumbers", owner.missingSceneDialogueAudioNumbers(scenes));
            response.put("videoRun", owner.hydrateVideoRunForResponse(run, safeTenantId, safeUserId));
            return response;
        }

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("runId", runId.toString());
        input.put("scriptId", script.getId().toString());
        input.put("availableSceneCount", availableInputs.size());
        input.put("totalSceneCount", scenes.size());
        CreatorGenerationJob job = generationJobService.startGenerationJob(
                JOB_SCREENPLAY_VIDEO_DIALOGUE_COMBINE,
                safeTenantId,
                safeUserId,
                script.getProjectId(),
                input
        );

        Path workDir = null;
        try {
            generationJobService.updateGenerationJobProgress(
                    job.getId(),
                    20,
                    "Preparing available scene dialogue audio",
                    owner.outputPayload(run, "Preparing available scene dialogue audio.")
            );
            workDir = Files.createTempDirectory("screenplay-dialogue-" + runId + "-");
            List<Path> audioPaths = new ArrayList<>();
            List<Map<String, Object>> includedInputs = new ArrayList<>();
            List<Integer> skippedSceneNumbers = new ArrayList<>(owner.missingSceneDialogueAudioNumbers(scenes));

            for (int index = 0; index < availableInputs.size(); index++) {
                Map<String, Object> audioInput = availableInputs.get(index);
                int sceneNumber = intValue(audioInput.get("sceneNumber"), index + 1);
                String contentType = firstText(audioInput.get("contentType"), "audio/mpeg");
                Path audioPath = workDir.resolve(
                        "%03d-scene-dialogue.%s".formatted(sceneNumber, owner.audioFileExtension(contentType))
                );
                try {
                    assetStorageService.downloadObjectToPath(
                            firstText(audioInput.get("bucket")),
                            firstText(audioInput.get("objectKey")),
                            audioPath
                    );
                    if (!Files.isRegularFile(audioPath) || Files.size(audioPath) <= 0) {
                        throw new IOException("Downloaded audio was empty.");
                    }
                    audioPaths.add(audioPath);
                    includedInputs.add(audioInput);
                } catch (RuntimeException | IOException ex) {
                    if (!skippedSceneNumbers.contains(sceneNumber)) {
                        skippedSceneNumbers.add(sceneNumber);
                    }
                    log.warn(
                            "Skipping unavailable scene dialogue audio runId={} sceneNumber={} objectKey={} errorType={} errorMessage={}",
                            runId,
                            sceneNumber,
                            firstText(audioInput.get("objectKey")),
                            ex.getClass().getSimpleName(),
                            ex.getMessage()
                    );
                }
            }

            if (audioPaths.isEmpty()) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "The generated scene audio files are not currently available in storage."
                );
            }

            generationJobService.updateGenerationJobProgress(
                    job.getId(),
                    55,
                    "Combining " + audioPaths.size() + " scene dialogue track" + (audioPaths.size() == 1 ? "" : "s"),
                    owner.outputPayload(run, "Combining available scene dialogue audio.")
            );
            Path outputPath = workDir.resolve("all-scene-dialogue.m4a");
            Path logPath = workDir.resolve("ffmpeg-dialogue-combine.log");
            owner.runFfmpeg(
                    owner.sceneDialogueAudioConcatCommand(audioPaths, outputPath),
                    logPath,
                    "Could not combine scene dialogue audio"
            );

            String combinedFingerprint = owner.sceneDialogueAudioFingerprint(includedInputs, scenes.size());
            List<Integer> includedSceneNumbers = includedInputs.stream()
                    .map(item -> intValue(item.get("sceneNumber"), 0))
                    .filter(number -> number > 0)
                    .toList();
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("provider", "local_ffmpeg");
            metadata.put("model", "ffmpeg-scene-dialogue-concat-v1");
            metadata.put("dialogueFingerprint", combinedFingerprint);
            metadata.put("layerType", "combined-scene-dialogue");
            metadata.put("includedSceneNumbers", includedSceneNumbers);
            metadata.put("skippedSceneNumbers", skippedSceneNumbers);
            metadata.put("totalSceneCount", scenes.size());

            Map<String, Object> asset = owner.storeAudioAssetFromPath(
                    script,
                    runId,
                    outputPath,
                    "audio/mp4",
                    "combined-scene-dialogue",
                    metadata
            );
            asset.put("combinedFingerprint", combinedFingerprint);
            asset.put("includedSceneNumbers", includedSceneNumbers);
            asset.put("includedSceneCount", includedInputs.size());
            asset.put("skippedSceneNumbers", skippedSceneNumbers);
            asset.put("totalSceneCount", scenes.size());
            asset.put("downloadFilename", owner.safeSlug(firstText(script.getTitle(), "screenplay")) + "-all-dialogue.m4a");
            asset.put("renderer", "local_ffmpeg");
            asset.put("model", "ffmpeg-scene-dialogue-concat-v1");

            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("status", "READY");
            summary.put("includedSceneNumbers", includedSceneNumbers);
            summary.put("includedSceneCount", includedInputs.size());
            summary.put("skippedSceneNumbers", skippedSceneNumbers);
            summary.put("skippedSceneCount", skippedSceneNumbers.size());
            summary.put("totalSceneCount", scenes.size());
            summary.put("combinedAt", OffsetDateTime.now().toString());

            run.put("combinedDialogueAudio", asset);
            run.put("combinedSceneDialogueAudio", asset);
            run.put("combinedDialogueTrack", firstText(
                    asset.get("assetUrl"),
                    asset.get("signedUrl"),
                    asset.get("publicUrl")
            ));
            run.put("combinedDialogueSummary", summary);
            run.put("updatedAt", OffsetDateTime.now().toString());
            run.put(
                    "message",
                    includedInputs.size() == scenes.size()
                            ? "All scene dialogue audio is combined and ready to download."
                            : includedInputs.size() + " of " + scenes.size() + " scene dialogue tracks were combined."
            );
            generationJobService.completeGenerationJob(
                    job.getId(),
                    owner.outputPayload(run, stringValue(run.get("message"), "Combined dialogue audio ready."))
            );

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "READY");
            response.put("reused", false);
            response.put("combinedDialogueAudio", asset);
            response.putAll(summary);
            response.put("videoRun", owner.hydrateVideoRunForResponse(run, safeTenantId, safeUserId));
            return response;
        } catch (RuntimeException ex) {
            String message = defaultString(ex.getMessage(), ex.getClass().getSimpleName());
            generationJobService.failGenerationJob(job.getId(), message, owner.outputPayload(run, message));
            throw ex;
        } catch (IOException ex) {
            String message = "Could not prepare scene dialogue audio.";
            generationJobService.failGenerationJob(job.getId(), message, owner.outputPayload(run, message));
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, message, ex);
        } finally {
            owner.deleteQuietly(workDir);
        }
    }
}
