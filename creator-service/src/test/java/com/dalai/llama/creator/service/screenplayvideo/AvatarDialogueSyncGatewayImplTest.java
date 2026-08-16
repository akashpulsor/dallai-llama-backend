package com.dalai.llama.creator.service.screenplayvideo;

import com.dalai.llama.creator.service.AvatarSceneDialogueService;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Characterization test for isAvatarDialogueRun() - confirmed (by tracing every call site) to be
 * shared between the dialogue-voice-cloning flow and ScreenplayVideoService.reconcilePreparedRunScenes.
 * Before this extraction there was exactly one copy; this test exists so a future edit to one
 * caller's expectations doesn't silently diverge from the other's, since both now share this one
 * implementation.
 */
class AvatarDialogueSyncGatewayImplTest {

    @Test
    void isAvatarDialogueRun_trueWhenFounderLedHybridEnabled() {
        AvatarDialogueSyncGatewayImpl gateway = new AvatarDialogueSyncGatewayImpl(mock(AvatarSceneDialogueService.class));
        Map<String, Object> run = new LinkedHashMap<>();
        run.put("founderLedHybridEnabled", true);
        assertTrue(gateway.isAvatarDialogueRun(run, List.of()));
    }

    @Test
    void isAvatarDialogueRun_trueWhenAnySceneIsTalkingHead() {
        AvatarDialogueSyncGatewayImpl gateway = new AvatarDialogueSyncGatewayImpl(mock(AvatarSceneDialogueService.class));
        // An empty run map short-circuits to false before scenes are even checked - runId is
        // enough to make this a non-empty, otherwise-ordinary run.
        Map<String, Object> run = new LinkedHashMap<>(Map.of("runId", "run-1"));
        Map<String, Object> scene = Map.of("generationMode", "talking_head");
        assertTrue(gateway.isAvatarDialogueRun(run, List.of(scene)));
    }

    @Test
    void isAvatarDialogueRun_falseForPlainAiGeneratedRun() {
        AvatarDialogueSyncGatewayImpl gateway = new AvatarDialogueSyncGatewayImpl(mock(AvatarSceneDialogueService.class));
        Map<String, Object> run = new LinkedHashMap<>(Map.of("runId", "run-1"));
        Map<String, Object> scene = Map.of("generationMode", "ai_generated");
        assertFalse(gateway.isAvatarDialogueRun(run, List.of(scene)));
    }
}
