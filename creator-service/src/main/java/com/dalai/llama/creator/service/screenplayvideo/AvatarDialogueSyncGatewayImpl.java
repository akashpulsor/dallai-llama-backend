package com.dalai.llama.creator.service.screenplayvideo;

import com.dalai.llama.creator.domain.entity.CreatorAvatarSceneDialogue;
import com.dalai.llama.creator.domain.entity.CreatorScript;
import com.dalai.llama.creator.service.AvatarSceneDialogueService;
import com.dalai.llama.creator.service.ScreenplayVideoService;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.dalai.llama.creator.service.screenplayvideo.MapCoercion.booleanValue;
import static com.dalai.llama.creator.service.screenplayvideo.MapCoercion.firstMap;
import static com.dalai.llama.creator.service.screenplayvideo.MapCoercion.firstText;

@Component
public class AvatarDialogueSyncGatewayImpl implements AvatarDialogueSyncGateway {

    private final AvatarSceneDialogueService avatarSceneDialogueService;

    public AvatarDialogueSyncGatewayImpl(AvatarSceneDialogueService avatarSceneDialogueService) {
        this.avatarSceneDialogueService = avatarSceneDialogueService;
    }

    @Override
    public CreatorAvatarSceneDialogue currentSource(CreatorScript script, UUID videoRunId, int sceneNumber) {
        if (avatarSceneDialogueService == null || script == null || videoRunId == null) {
            return null;
        }
        return avatarSceneDialogueService.currentSources(
                script.getTenantId(),
                script.getUserId(),
                videoRunId
        ).get(sceneNumber);
    }

    @Override
    public void applyRecord(Map<String, Object> scene, CreatorAvatarSceneDialogue source, CreatorAvatarSceneDialogue selected) {
        if (scene == null || source == null || selected == null) {
            return;
        }
        ScreenplayVideoService.applySceneDialogue(scene, selected.getDialogueText());
        applyIdentity(scene, source, selected);
        scene.put("sourceDialogueScript", source.getDialogueText());
        scene.put("sourceDialogueLanguage", source.getLanguage());
        scene.put("dialogueLanguage", selected.getLanguage());
        scene.put("languageCode", selected.getLanguageCode());
        scene.put(
                "dialogueLocalizationStatus",
                Boolean.TRUE.equals(selected.getSource()) ? "NOT_REQUIRED" : "COMPLETED"
        );
        scene.put("dialogueTranslationApplied", !Boolean.TRUE.equals(selected.getSource()));
        scene.put("dialogueSource", "creator_avatar_scene_dialogues");
        attachVariants(scene, source, selected);
    }

    @Override
    public boolean isAvatarDialogueRun(Map<String, Object> run, List<Map<String, Object>> scenes) {
        if (run == null || run.isEmpty()) {
            return false;
        }
        if (booleanValue(run.get("founderLedHybridEnabled"), false)
                || "full_founder".equalsIgnoreCase(firstText(run.get("hybridSceneMode")))
                || !firstMap(run.get("founderAvatarProfile"), run.get("founderKit")).isEmpty()) {
            return true;
        }
        List<Map<String, Object>> safeScenes = scenes == null ? List.of() : scenes;
        return safeScenes.stream().anyMatch(scene ->
                "talking_head".equalsIgnoreCase(firstText(scene.get("generationMode")))
                        || !firstMap(scene.get("founderAvatarProfile")).isEmpty()
                        || !firstText(
                                scene.get("dialogueCloneStatus"),
                                scene.get("avatarDialogueId"),
                                scene.get("avatarRootDialogueId")
                        ).isBlank()
        );
    }

    private void applyIdentity(Map<String, Object> scene, CreatorAvatarSceneDialogue source, CreatorAvatarSceneDialogue selected) {
        scene.put("avatarDialogueId", selected.getId().toString());
        scene.put("avatarRootDialogueId", source.getRootDialogueId().toString());
        scene.put("sourceAvatarDialogueId", source.getId().toString());
        scene.put("dialogueLanguageRecordId", selected.getId().toString());
        scene.put("dialogueSpeaker", firstText(selected.getSpeaker(), source.getSpeaker()));
        scene.put("dialogueSourceKind", source.getSourceKind());
        scene.put("dialogueSourcePath", source.getSourcePath());
        scene.put("dialogueVersion", selected.getVersionNumber());
    }

    private void attachVariants(Map<String, Object> scene, CreatorAvatarSceneDialogue source, CreatorAvatarSceneDialogue selected) {
        if (avatarSceneDialogueService == null || source == null || selected == null) {
            return;
        }
        List<CreatorAvatarSceneDialogue> currentVariants = avatarSceneDialogueService.currentVariants(
                source.getRootDialogueId()
        );
        if (currentVariants.isEmpty()) {
            currentVariants = List.of(source);
        }
        List<Map<String, Object>> variants = currentVariants.stream()
                .map(row -> variant(row, row.getId().equals(selected.getId())))
                .toList();
        scene.put("dialogueVariants", variants);
        scene.put("selectedDialogueId", selected.getId().toString());
        scene.put("selectedDialogueLanguage", selected.getLanguage());
    }

    private Map<String, Object> variant(CreatorAvatarSceneDialogue row, boolean selected) {
        Map<String, Object> variant = new LinkedHashMap<>();
        variant.put("id", row.getId().toString());
        variant.put("rootDialogueId", row.getRootDialogueId().toString());
        variant.put("language", row.getLanguage());
        variant.put("languageKey", row.getLanguageKey());
        variant.put("languageCode", row.getLanguageCode());
        variant.put("dialogueText", row.getDialogueText());
        variant.put("speaker", row.getSpeaker());
        variant.put("source", Boolean.TRUE.equals(row.getSource()));
        variant.put("selected", selected);
        variant.put("version", row.getVersionNumber());
        return variant;
    }
}
