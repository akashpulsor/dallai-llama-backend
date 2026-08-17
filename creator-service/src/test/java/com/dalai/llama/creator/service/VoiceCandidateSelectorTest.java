package com.dalai.llama.creator.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Characterization tests for VoiceCandidateSelector - the extraction of
 * ScreenplayVideoService's dialogue-voice-profile selection cluster.
 */
class VoiceCandidateSelectorTest {

    private final VoiceCandidateSelector selector = new VoiceCandidateSelector();

    @Test
    void voiceProviderFrom_collapsesKnownSynonymsAndDefaultsToEmpty() {
        assertEquals("dalai_llama", selector.voiceProviderFrom("local"));
        assertEquals("dalai_llama", selector.voiceProviderFrom("open_source"));
        assertEquals("elevenlabs", selector.voiceProviderFrom("eleven-labs"));
        assertEquals("google_chirp", selector.voiceProviderFrom("gemini"));
        assertEquals("", selector.voiceProviderFrom("something_else"));
        assertEquals("", selector.voiceProviderFrom(null));
    }

    @Test
    void dialogueVoiceProfile_returnsEmptyProfileWhenNoCandidatesAndNoExplicitRequest() {
        Map<String, Object> profile = selector.dialogueVoiceProfile(Map.of(), Map.of(), Map.of());
        assertFalse(profile.containsKey("voiceGender"));
        assertFalse(profile.containsKey("speakerName"));
        assertEquals("configured_default", profile.get("selectionSource"));
    }

    @Test
    void dialogueVoiceProfile_explicitRequestOverridesEverything() {
        Map<String, Object> inputPayload = Map.of(
                "voiceGender", "female",
                "speakerName", "Priya"
        );
        Map<String, Object> profile = selector.dialogueVoiceProfile(inputPayload, Map.of(), Map.of());
        assertEquals("female", profile.get("voiceGender"));
        assertEquals("Priya", profile.get("speakerName"));
        assertEquals("user_selected_cast", profile.get("selectionSource"));
    }

    @Test
    void dialogueVoiceProfile_selectsHighestScoringCastCandidateByRole() {
        Map<String, Object> supportingCharacter = Map.of(
                "role", "supporting",
                "name", "Extra One"
        );
        Map<String, Object> mainCharacter = Map.of(
                "role", "main",
                "gender", "male",
                "name", "Hero"
        );
        Map<String, Object> run = Map.of(
                "characterCastMappings", List.of(supportingCharacter, mainCharacter)
        );
        Map<String, Object> profile = selector.dialogueVoiceProfile(Map.of(), run, Map.of());
        assertEquals("male", profile.get("voiceGender"));
        assertEquals("Hero", profile.get("speakerName"));
        assertEquals("cast_mapping", profile.get("selectionSource"));
    }

    @Test
    void dialogueVoiceProfile_normalizesGenderVariantsFromCastData() {
        Map<String, Object> character = Map.of("role", "main", "gender", "Woman", "name", "Lead");
        Map<String, Object> run = Map.of("characterCastMappings", List.of(character));
        Map<String, Object> profile = selector.dialogueVoiceProfile(Map.of(), run, Map.of());
        assertEquals("female", profile.get("voiceGender"));
    }
}
