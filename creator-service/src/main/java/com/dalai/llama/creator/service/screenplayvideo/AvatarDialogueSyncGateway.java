package com.dalai.llama.creator.service.screenplayvideo;

import com.dalai.llama.creator.domain.entity.CreatorAvatarSceneDialogue;
import com.dalai.llama.creator.domain.entity.CreatorScript;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Confirmed by tracing every call site (not guessed by name/proximity): this subgroup is used by
 * both the dialogue-voice-cloning flow AND ScreenplayVideoService.reconcilePreparedRunScenes
 * (initial-run assembly, unrelated to voice cloning) - a genuine shared concern, so both depend on
 * this interface instead of one depending on the other. Deliberately narrower than the full
 * original synchronizeAvatarDialogueSources()/draftDialogueVariant() group: those two specifically
 * call back into ScreenplayVideoService-only orchestration (reconcilePreparedRunScenes itself),
 * which would create a circular dependency if pulled in here - they stay in ScreenplayVideoService,
 * now calling this gateway for the pieces that actually are self-contained.
 */
public interface AvatarDialogueSyncGateway {

    CreatorAvatarSceneDialogue currentSource(CreatorScript script, UUID videoRunId, int sceneNumber);

    void applyRecord(Map<String, Object> scene, CreatorAvatarSceneDialogue source, CreatorAvatarSceneDialogue selected);

    boolean isAvatarDialogueRun(Map<String, Object> run, List<Map<String, Object>> scenes);
}
