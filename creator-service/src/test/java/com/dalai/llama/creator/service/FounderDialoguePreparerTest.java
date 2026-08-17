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
 * Characterization tests for FounderDialoguePreparer - the extraction of
 * ScreenplayVideoService.prepareFounderEnglishDialogue(), zero coverage before this move.
 */
class FounderDialoguePreparerTest {

    private final ScreenplayVideoService owner = mock(ScreenplayVideoService.class);
    private final FounderDialoguePreparer preparer = new FounderDialoguePreparer(owner);

    @Test
    void throwsBadRequest_whenConsentNotConfirmed() {
        when(owner.loadScript(any(), any(), any())).thenReturn(script());
        when(owner.founderAvatarProfile(any(), any(), any())).thenReturn(Map.of("consentConfirmed", false));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                preparer.prepareFounderEnglishDialogue(UUID.randomUUID(), Map.of(), "tenant", "user"));
        assertTrue(ex.getReason().contains("Confirm founder consent"));
    }

    @Test
    void throwsBadRequest_whenNoDialogueFoundAnywhere() {
        when(owner.loadScript(any(), any(), any())).thenReturn(script());
        when(owner.founderAvatarProfile(any(), any(), any())).thenReturn(consentedProfile());
        when(owner.sourceScenes(any(), any())).thenReturn(List.of());
        when(owner.audioPackVoiceText(any(), any(), any())).thenReturn("");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                preparer.prepareFounderEnglishDialogue(UUID.randomUUID(), Map.of(), "tenant", "user"));
        assertTrue(ex.getReason().contains("does not contain dialogue"));
    }

    @Test
    void usesExplicitRequestDialogueWithoutConsultingSourceScenes() {
        when(owner.loadScript(any(), any(), any())).thenReturn(script());
        when(owner.founderAvatarProfile(any(), any(), any())).thenReturn(consentedProfile());
        when(owner.sameLanguage(anyString(), anyString())).thenReturn(true);
        when(owner.dialogueTextForScene(any())).thenReturn("Hello from the founder.");
        when(owner.estimatedDialogueSeconds(anyString())).thenReturn(5);

        Map<String, Object> request = Map.of("dialogueText", "Hello from the founder.");
        Map<String, Object> response = preparer.prepareFounderEnglishDialogue(UUID.randomUUID(), request, "tenant", "user");

        assertEquals("READY", response.get("status"));
        assertEquals("Hello from the founder.", response.get("translatedDialogue"));
        verify(owner, never()).sourceScenes(any(), any());
    }

    @Test
    void fallsBackToSourceScenesWhenNoExplicitDialogueRequested() {
        when(owner.loadScript(any(), any(), any())).thenReturn(script());
        when(owner.founderAvatarProfile(any(), any(), any())).thenReturn(consentedProfile());
        Map<String, Object> shotScene = Map.of("id", "shot-1", "dialogueScript", "Shot dialogue.");
        when(owner.sourceScenes(any(), any())).thenReturn(List.of(shotScene));
        when(owner.sameLanguage(anyString(), anyString())).thenReturn(true);
        when(owner.dialogueTextForScene(any())).thenReturn("Shot dialogue.");

        Map<String, Object> response = preparer.prepareFounderEnglishDialogue(UUID.randomUUID(), Map.of(), "tenant", "user");

        assertEquals("Shot dialogue.", response.get("translatedDialogue"));
        verify(owner, never()).audioPackVoiceText(any(), any(), any());
    }

    @Test
    void fallsBackToScreenplayDialogueWhenSourceScenesHaveNoSpokenText() {
        when(owner.loadScript(any(), any(), any())).thenReturn(script());
        when(owner.founderAvatarProfile(any(), any(), any())).thenReturn(consentedProfile());
        when(owner.sourceScenes(any(), any())).thenReturn(List.of());
        when(owner.audioPackVoiceText(any(), any(), any())).thenReturn("Screenplay fallback dialogue.");
        when(owner.sameLanguage(anyString(), anyString())).thenReturn(true);
        when(owner.dialogueTextForScene(any())).thenReturn("Screenplay fallback dialogue.");
        when(owner.estimatedDialogueSeconds(anyString())).thenReturn(8);

        Map<String, Object> response = preparer.prepareFounderEnglishDialogue(UUID.randomUUID(), Map.of(), "tenant", "user");

        assertEquals("Screenplay fallback dialogue.", response.get("translatedDialogue"));
    }

    @Test
    void skipsLocalizationWhenSourceLanguageIsAlreadyEnglish() {
        when(owner.loadScript(any(), any(), any())).thenReturn(script());
        when(owner.founderAvatarProfile(any(), any(), any())).thenReturn(consentedProfile());
        when(owner.sameLanguage(anyString(), anyString())).thenReturn(true);
        when(owner.dialogueTextForScene(any())).thenReturn("Already English.");
        when(owner.estimatedDialogueSeconds(anyString())).thenReturn(5);

        preparer.prepareFounderEnglishDialogue(UUID.randomUUID(), Map.of("dialogueText", "Already English."), "tenant", "user");

        verify(owner, never()).localizeDialogueScenes(any(), any(), any(), any(), any(), any());
    }

    @Test
    void callsLocalizeDialogueScenesWhenSourceLanguageDiffersFromEnglish() {
        when(owner.loadScript(any(), any(), any())).thenReturn(script());
        when(owner.founderAvatarProfile(any(), any(), any())).thenReturn(consentedProfile());
        when(owner.sameLanguage(anyString(), anyString())).thenReturn(false);
        Map<String, Object> localizedScene = Map.of("id", "founder-dialogue", "dialogueScript", "Localized English.");
        when(owner.localizeDialogueScenes(any(), any(), any(), any(), any(), any())).thenReturn(List.of(localizedScene));
        when(owner.dialogueTextForScene(any())).thenReturn("Localized English.");
        when(owner.estimatedDialogueSeconds(anyString())).thenReturn(5);

        Map<String, Object> response = preparer.prepareFounderEnglishDialogue(
                UUID.randomUUID(), Map.of("dialogueText", "Namaste from the founder.", "sourceDialogueLanguage", "Hindi"), "tenant", "user"
        );

        assertEquals("Localized English.", response.get("translatedDialogue"));
        verify(owner).localizeDialogueScenes(any(), any(), any(), any(), any(), any());
    }

    @Test
    void throwsBadGateway_whenLocalizedScenesHaveNoSpokenLines() {
        when(owner.loadScript(any(), any(), any())).thenReturn(script());
        when(owner.founderAvatarProfile(any(), any(), any())).thenReturn(consentedProfile());
        when(owner.sameLanguage(anyString(), anyString())).thenReturn(false);
        when(owner.localizeDialogueScenes(any(), any(), any(), any(), any(), any())).thenReturn(List.of(Map.of("id", "founder-dialogue")));
        // First call checks the pre-localization request-derived scene (must be non-blank so the
        // audioPackVoiceText fallback isn't triggered instead); second call is the post-localization
        // loop that must see blank text to exercise the BAD_GATEWAY path under test.
        when(owner.dialogueTextForScene(any())).thenReturn("Something.").thenReturn("");
        when(owner.estimatedDialogueSeconds(anyString())).thenReturn(5);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                preparer.prepareFounderEnglishDialogue(
                        UUID.randomUUID(), Map.of("dialogueText", "Something.", "sourceDialogueLanguage", "Hindi"), "tenant", "user"
                ));
        assertTrue(ex.getReason().contains("no spoken lines"));
    }

    @Test
    void setsVoiceModelToClientRvcEnglishAndAttachesUpdatedProfileToScript() {
        CreatorScript script = script();
        when(owner.loadScript(any(), any(), any())).thenReturn(script);
        when(owner.founderAvatarProfile(any(), any(), any())).thenReturn(consentedProfile());
        when(owner.sameLanguage(anyString(), anyString())).thenReturn(true);
        when(owner.dialogueTextForScene(any())).thenReturn("Hello.");
        when(owner.estimatedDialogueSeconds(anyString())).thenReturn(5);

        Map<String, Object> response = preparer.prepareFounderEnglishDialogue(
                UUID.randomUUID(), Map.of("dialogueText", "Hello."), "tenant", "user"
        );

        assertEquals("client_rvc_english", response.get("voiceModel"));
        @SuppressWarnings("unchecked")
        Map<String, Object> founderAvatarProfile = (Map<String, Object>) response.get("founderAvatarProfile");
        @SuppressWarnings("unchecked")
        Map<String, Object> localModels = (Map<String, Object>) founderAvatarProfile.get("localModels");
        assertEquals("client_rvc_english", localModels.get("voiceModel"));
        verify(owner).invalidateAvatarPreview(any());
        verify(owner).attachFounderAvatarToScript(any(), any(), any());
    }

    private Map<String, Object> consentedProfile() {
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("consentConfirmed", true);
        return profile;
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
