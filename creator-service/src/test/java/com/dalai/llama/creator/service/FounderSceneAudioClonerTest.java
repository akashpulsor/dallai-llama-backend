package com.dalai.llama.creator.service;

import com.dalai.llama.creator.domain.entity.CreatorScript;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Characterization tests for the scene dialogue audio reuse-vs-regenerate decision moved out of
 * ScreenplayVideoService.prepareFounderSceneAudio() - zero coverage before this extraction, per the
 * refactor plan's "characterization tests before moving zero-coverage code" rule.
 */
class FounderSceneAudioClonerTest {

    private final ScreenplayVideoService owner = mock(ScreenplayVideoService.class);
    private final CreatorAiService creatorAiService = mock(CreatorAiService.class);
    private final GoogleChirpVoiceGenerationService voiceGenerationService = mock(GoogleChirpVoiceGenerationService.class);
    private final FounderSceneAudioCloner cloner = new FounderSceneAudioCloner(owner, creatorAiService, voiceGenerationService);

    @Test
    void returnsEmpty_whenSceneIsNotTalkingHead() {
        when(owner.generationModeFor(any(), any(), any())).thenReturn("full_video");

        Map<String, Object> result = cloner.prepareFounderSceneAudio(
                script(), UUID.randomUUID(), UUID.randomUUID(), Map.of(), scene(), true
        );

        assertTrue(result.isEmpty());
        verify(voiceGenerationService, never()).generateVoice(anyString(), any());
    }

    @Test
    void returnsEmpty_whenProviderIsNotDalaiLlama() {
        when(owner.generationModeFor(any(), any(), any())).thenReturn("talking_head");
        when(owner.providerForSceneGeneration(any(), any(), any())).thenReturn("some_other_provider");

        Map<String, Object> result = cloner.prepareFounderSceneAudio(
                script(), UUID.randomUUID(), UUID.randomUUID(), Map.of(), scene(), true
        );

        assertTrue(result.isEmpty());
        verify(voiceGenerationService, never()).generateVoice(anyString(), any());
    }

    @Test
    void throwsConflict_whenConsentNotConfirmed() {
        talkingHeadDalaiLlama();
        when(owner.founderAvatarProfile(any(), any(), any())).thenReturn(Map.of("consentConfirmed", false));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                cloner.prepareFounderSceneAudio(script(), UUID.randomUUID(), UUID.randomUUID(), Map.of(), scene(), true)
        );
        assertTrue(ex.getReason().contains("consent"));
    }

    @Test
    void reusesExistingAsset_whenPersistedCloneMatchesByFingerprint() {
        talkingHeadDalaiLlama();
        UUID runId = UUID.randomUUID();
        Map<String, Object> founderProfile = new LinkedHashMap<>();
        founderProfile.put("consentConfirmed", true);
        founderProfile.put("avatarPreviewStatus", "APPROVED");
        when(owner.founderAvatarProfile(any(), any(), any())).thenReturn(founderProfile);
        when(owner.requireSceneVoiceMethod(any())).thenReturn("fal_minimax_voice_clone");
        when(owner.normalizeLocalTalkingAvatarModel(any())).thenReturn("fal_heygen_avatar4");
        when(owner.dialogueTextForScene(any())).thenReturn("Hello from the founder.");
        when(owner.languageCodeFor(any())).thenReturn("en-IN");
        when(owner.sameLanguage(any(), any())).thenReturn(true);
        when(owner.providerVoiceIdForMethod(any(), any())).thenReturn("voice-1");
        when(owner.stableFingerprint(any(String[].class))).thenReturn("fingerprint-abc");

        Map<String, Object> existingAsset = new LinkedHashMap<>();
        existingAsset.put("objectKey", "founders/scene-1.wav");
        existingAsset.put("dialogueFingerprint", "fingerprint-abc");

        Map<String, Object> scene = scene();
        scene.put("dialogueAudio", existingAsset);
        scene.put("dialogueCloneStatus", "APPROVED");

        Map<String, Object> result = cloner.prepareFounderSceneAudio(script(), runId, UUID.randomUUID(), Map.of(), scene, true);

        assertEquals(existingAsset, result);
        verify(voiceGenerationService, never()).generateVoice(anyString(), any());
        verify(creatorAiService, never()).assertWalletBalanceForModelRun(anyString(), any());
    }

    @Test
    void throwsConflict_whenNotAllowedToGenerateAndNoAcceptedClone() {
        talkingHeadDalaiLlama();
        Map<String, Object> founderProfile = new LinkedHashMap<>();
        founderProfile.put("consentConfirmed", true);
        founderProfile.put("avatarPreviewStatus", "APPROVED");
        when(owner.founderAvatarProfile(any(), any(), any())).thenReturn(founderProfile);
        when(owner.requireSceneVoiceMethod(any())).thenReturn("fal_minimax_voice_clone");
        when(owner.normalizeLocalTalkingAvatarModel(any())).thenReturn("fal_heygen_avatar4");
        when(owner.dialogueTextForScene(any())).thenReturn("Hello from the founder.");
        when(owner.languageCodeFor(any())).thenReturn("en-IN");
        when(owner.sameLanguage(any(), any())).thenReturn(true);
        when(owner.providerVoiceIdForMethod(any(), any())).thenReturn("voice-1");
        when(owner.stableFingerprint(any(String[].class))).thenReturn("fingerprint-abc");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                cloner.prepareFounderSceneAudio(script(), UUID.randomUUID(), UUID.randomUUID(), Map.of(), scene(), false)
        );
        assertTrue(ex.getReason().contains("Clone this scene dialogue"));
        verify(voiceGenerationService, never()).generateVoice(anyString(), any());
    }

    @Test
    void generatesAndStoresNewVoice_whenNoPersistedCloneMatches() {
        talkingHeadDalaiLlama();
        UUID runId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        Map<String, Object> founderProfile = new LinkedHashMap<>();
        founderProfile.put("consentConfirmed", true);
        founderProfile.put("avatarPreviewStatus", "APPROVED");
        when(owner.founderAvatarProfile(any(), any(), any())).thenReturn(founderProfile);
        when(owner.requireSceneVoiceMethod(any())).thenReturn("fal_minimax_voice_clone");
        when(owner.normalizeLocalTalkingAvatarModel(any())).thenReturn("fal_heygen_avatar4");
        when(owner.dialogueTextForScene(any())).thenReturn("Hello from the founder.");
        when(owner.languageCodeFor(any())).thenReturn("en-IN");
        when(owner.sameLanguage(any(), any())).thenReturn(false);
        when(owner.providerVoiceIdForMethod(any(), any())).thenReturn("voice-1");
        when(owner.stableFingerprint(any(String[].class))).thenReturn("fingerprint-new");
        when(owner.applyPronunciationGuide(any(), any())).thenReturn("Hello from the founder.");

        GoogleChirpVoiceGenerationService.GeneratedVoice generatedVoice = new GoogleChirpVoiceGenerationService.GeneratedVoice(
                new byte[]{1, 2, 3},
                "audio/mpeg",
                new LinkedHashMap<>(),
                Map.of(),
                Map.of()
        );
        when(voiceGenerationService.generateVoice(anyString(), any())).thenReturn(generatedVoice);

        Map<String, Object> storedAsset = Map.of("assetId", "asset-1");
        when(owner.storeAudioAsset(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(storedAsset);

        Map<String, Object> result = cloner.prepareFounderSceneAudio(script(), runId, jobId, Map.of(), scene(), true);

        assertEquals(storedAsset, result);
        verify(creatorAiService).assertWalletBalanceForModelRun(anyString(), any());
        verify(creatorAiService).publishProviderUsageDebit(anyString(), anyString(), anyString(), any(), any(), anyString());
    }

    private void talkingHeadDalaiLlama() {
        when(owner.generationModeFor(any(), any(), any())).thenReturn("talking_head");
        when(owner.providerForSceneGeneration(any(), any(), any())).thenReturn("dalai_llama");
    }

    private Map<String, Object> scene() {
        Map<String, Object> scene = new LinkedHashMap<>();
        scene.put("id", "scene-1");
        return scene;
    }

    private CreatorScript script() {
        return CreatorScript.builder()
                .id(UUID.randomUUID())
                .tenantId("tenant")
                .userId("user")
                .title("Founder script")
                .scriptPayload(new LinkedHashMap<>())
                .shots(List.of())
                .status("GENERATED")
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .build();
    }
}
