package com.dalai.llama.creator.service;

import com.dalai.llama.creator.domain.entity.CreatorScript;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.dalai.llama.creator.service.screenplayvideo.MapCoercion.*;

/**
 * Extraction of ScreenplayVideoService's prepareFounderEnglishDialogue endpoint - translating the
 * founder's dialogue (from an explicit request, existing script shots, or the screenplay itself)
 * into English and re-pointing the founder avatar profile at the client_rvc_english voice clone
 * method. Owner-scoped (same package, owner back-reference) for the same reason
 * DialogueVoiceCloner is: this leans on loadScript, founderAvatarProfile, sourceScenes,
 * dialogueTextForScene, audioPackVoiceText, sameLanguage, localizeDialogueScenes,
 * invalidateAvatarPreview, and attachFounderAvatarToScript - shared "run/script domain"
 * primitives used throughout ScreenplayVideoService, not exclusive to this one flow.
 */
final class FounderDialoguePreparer {

    private final ScreenplayVideoService owner;

    FounderDialoguePreparer(ScreenplayVideoService owner) {
        this.owner = owner;
    }

    Map<String, Object> prepareFounderEnglishDialogue(
            UUID scriptId,
            Map<String, Object> requestBody,
            String tenantId,
            String userId
    ) {
        CreatorScript script = owner.loadScript(scriptId, tenantId, userId);
        Map<String, Object> request = copyMap(requestBody);
        Map<String, Object> scriptPayload = copyMap(script.getScriptPayload());
        Map<String, Object> profile = owner.founderAvatarProfile(
                request,
                scriptPayload,
                firstMap(scriptPayload.get("creatorContext"))
        );
        if (!booleanValue(profile.get("consentConfirmed"), false)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Confirm founder consent before preparing dialogue for the client voice."
            );
        }

        String sourceLanguage = firstText(
                request.get("sourceDialogueLanguage"),
                script.getDialogueLanguage(),
                scriptPayload.get("dialogueLanguage"),
                "Hinglish"
        );
        String requestedDialogue = firstText(
                request.get("dialogueText"),
                request.get("avatarScript"),
                request.get("spokenText"),
                profile.get("avatarScript"),
                profile.get("spokenText")
        );
        List<Map<String, Object>> sourceDialogueScenes;
        if (!requestedDialogue.isBlank()) {
            Map<String, Object> scene = new LinkedHashMap<>();
            scene.put("id", "founder-dialogue");
            scene.put("sceneNumber", 1);
            scene.put("durationSeconds", Math.max(10, Math.min(60, owner.estimatedDialogueSeconds(requestedDialogue))));
            scene.put("dialogueScript", truncate(requestedDialogue, 4800));
            sourceDialogueScenes = List.of(scene);
        } else {
            sourceDialogueScenes = owner.sourceScenes(script, request);
        }
        if (sourceDialogueScenes.isEmpty()
                || sourceDialogueScenes.stream().allMatch(scene -> owner.dialogueTextForScene(scene).isBlank())) {
            String scriptDialogue = owner.audioPackVoiceText(request, Map.of(), script);
            if (scriptDialogue.isBlank()) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "The screenplay does not contain dialogue to translate."
                );
            }
            Map<String, Object> scene = new LinkedHashMap<>();
            scene.put("id", "founder-dialogue");
            scene.put("sceneNumber", 1);
            scene.put("durationSeconds", Math.max(10, Math.min(60, owner.estimatedDialogueSeconds(scriptDialogue))));
            scene.put("dialogueScript", scriptDialogue);
            sourceDialogueScenes = List.of(scene);
        }

        List<Map<String, Object>> englishScenes = owner.sameLanguage(sourceLanguage, "English")
                ? sourceDialogueScenes
                : owner.localizeDialogueScenes(
                        script,
                        sourceDialogueScenes,
                        sourceLanguage,
                        "English",
                        "en-IN",
                        null
                );
        StringBuilder englishDialogue = new StringBuilder();
        for (Map<String, Object> scene : englishScenes) {
            String line = owner.dialogueTextForScene(scene);
            if (line.isBlank()) {
                continue;
            }
            if (!englishDialogue.isEmpty()) {
                englishDialogue.append(System.lineSeparator());
            }
            englishDialogue.append(line);
        }
        if (englishDialogue.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "English dialogue preparation returned no spoken lines."
            );
        }

        String preparedDialogue = truncate(englishDialogue.toString(), 12000);
        String profileId = firstText(
                request.get("voiceProfileId"),
                profile.get("voiceProfileId"),
                firstMap(profile.get("localModels")).get("voiceProfileId"),
                "founder_female_v1"
        );
        Map<String, Object> localModels = new LinkedHashMap<>(firstMap(profile.get("localModels")));
        localModels.put("voiceModel", "client_rvc_english");
        localModels.put("voiceProfileId", profileId);
        profile.put("localModels", localModels);
        profile.put("voiceProfileId", profileId);
        profile.put("sourceDialogueLanguage", sourceLanguage);
        profile.put("sourceAvatarScript", requestedDialogue);
        profile.put("avatarScript", preparedDialogue);
        profile.put("spokenText", preparedDialogue);
        profile.put("language", "English");
        profile.put("languageCode", "en-IN");
        profile.put("voiceLanguageMode", "english_indian");
        profile.put("voiceLanguage", "English");
        profile.put("voiceLanguageCode", "en-IN");
        profile.put("voicePreviewText", truncate(preparedDialogue, 240));
        profile.put("voiceApprovalStatus", "NOT_REQUESTED");
        profile.put("voiceTranslationStatus", "COMPLETED");
        profile.put("voiceTranslationPreparedAt", OffsetDateTime.now().toString());
        owner.invalidateAvatarPreview(profile);
        profile.remove("voicePreviewAsset");
        profile.remove("voiceApprovedAt");
        profile.remove("voiceApprovedBy");
        profile.remove("providerVoiceId");
        profile.remove("customVoiceId");
        profile.remove("minimaxVoiceId");
        owner.attachFounderAvatarToScript(script, firstMap(profile.get("sourceAsset")), profile);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "READY");
        response.put("sourceLanguage", sourceLanguage);
        response.put("targetLanguage", "English");
        response.put("languageCode", "en-IN");
        response.put("voiceModel", "client_rvc_english");
        response.put("voiceProfileId", profileId);
        response.put("translatedDialogue", preparedDialogue);
        response.put("scenes", englishScenes);
        response.put("founderAvatarProfile", profile);
        return response;
    }
}
