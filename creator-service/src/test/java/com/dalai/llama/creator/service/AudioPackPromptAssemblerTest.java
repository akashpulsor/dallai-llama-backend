package com.dalai.llama.creator.service;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Characterization tests for AudioPackPromptAssembler - the extraction of
 * ScreenplayVideoService's audio-pack prompt-assembly cluster (dialogue/voiceover text
 * resolution, audio request type normalization, and background-music prompt generation).
 */
class AudioPackPromptAssemblerTest {

    private final AudioPackPromptAssembler assembler = new AudioPackPromptAssembler();

    @Test
    void audioPackVoiceText_prefersExplicitRequestTextOverEverythingElse() {
        Map<String, Object> inputPayload = Map.of("voiceText", "Say this exact line.");
        Map<String, Object> run = Map.of("voiceoverScript", "ignored planned text");
        assertEquals("Say this exact line.", assembler.audioPackVoiceText(inputPayload, run, null));
    }

    @Test
    void audioPackVoiceText_fallsBackToPlannedDialogueScriptWhenNoExplicitRequest() {
        Map<String, Object> run = Map.of("dialogueScript", "Planned line from run.");
        assertEquals("Planned line from run.", assembler.audioPackVoiceText(Map.of(), run, null));
    }

    @Test
    void audioPackVoiceText_treatsAutoPlannedTextAsNotSet() {
        Map<String, Object> run = Map.of("dialogueScript", "auto", "scenes", List.of(Map.of("dialogue", "Scene line.")));
        assertEquals("Scene line.", assembler.audioPackVoiceText(Map.of(), run, null));
    }

    @Test
    void audioPackVoiceText_fallsBackToSrtCuesWhenNoPlannedText() {
        Map<String, Object> run = Map.of("srtCues", List.of(Map.of("text", "Cue one."), Map.of("text", "Cue two.")));
        String result = assembler.audioPackVoiceText(Map.of(), run, null);
        assertTrue(result.contains("Cue one."));
        assertTrue(result.contains("Cue two."));
    }

    @Test
    void audioPackVoiceText_fallsBackToSceneLinesWhenNoCues() {
        Map<String, Object> run = Map.of("scenes", List.of(Map.of("caption", "Caption line.")));
        assertEquals("Caption line.", assembler.audioPackVoiceText(Map.of(), run, null));
    }

    @Test
    void audioPackVoiceText_fallsBackToScriptTextAsLastResort() {
        assertEquals("", assembler.audioPackVoiceText(Map.of(), Map.of(), null));
    }

    @Test
    void applyAudioRequestType_dialogueTypeDisablesMusicAndEnablesVoice() {
        Map<String, Object> inputPayload = new HashMap<>();
        inputPayload.put("audioRequestType", "voiceover");
        assembler.applyAudioRequestType(inputPayload);
        assertEquals("dialogue", inputPayload.get("audioRequestType"));
        assertEquals(true, inputPayload.get("generateDialogue"));
        assertEquals(true, inputPayload.get("generateVoice"));
        assertEquals("none", inputPayload.get("musicSource"));
        assertEquals(false, inputPayload.get("generateMusic"));
    }

    @Test
    void applyAudioRequestType_backgroundMusicTypeDisablesDialogueAndResolvesMusicSource() {
        Map<String, Object> inputPayload = new HashMap<>();
        inputPayload.put("audioRequestType", "bgm");
        inputPayload.put("musicSource", "lyria");
        assembler.applyAudioRequestType(inputPayload);
        assertEquals("background_music", inputPayload.get("audioRequestType"));
        assertEquals(false, inputPayload.get("generateDialogue"));
        assertEquals(false, inputPayload.get("generateVoice"));
        assertEquals("ai_generated", inputPayload.get("musicSource"));
        assertEquals(true, inputPayload.get("generateMusic"));
        assertEquals(true, inputPayload.get("generateAiMusic"));
    }

    @Test
    void applyAudioRequestType_unrecognizedTypeLeavesPayloadUntouched() {
        Map<String, Object> inputPayload = new HashMap<>();
        inputPayload.put("audioRequestType", "not_a_real_type");
        assembler.applyAudioRequestType(inputPayload);
        assertFalse(inputPayload.containsKey("generateDialogue"));
    }

    @Test
    void applyAudioRequestType_nullPayloadDoesNotThrow() {
        assembler.applyAudioRequestType(null);
    }

    @Test
    void normalizeMusicSource_collapsesKnownSynonymsAndDefaultsToFreeLicensed() {
        assertEquals("free_licensed", assembler.normalizeMusicSource(null));
        assertEquals("free_licensed", assembler.normalizeMusicSource("royalty-free"));
        assertEquals("ai_generated", assembler.normalizeMusicSource("ai"));
        assertEquals("ai_generated", assembler.normalizeMusicSource("google_lyria"));
        assertEquals("none", assembler.normalizeMusicSource("no-music"));
        assertEquals("free_licensed", assembler.normalizeMusicSource("something_unrecognized"));
    }

    @Test
    void audioPackMusicPrompt_usesExplicitPromptAsCreativeDirectionWhenProvided() {
        Map<String, Object> inputPayload = Map.of("musicPrompt", "Upbeat synthwave for a tech launch.");
        String prompt = assembler.audioPackMusicPrompt(inputPayload, Map.of(), Map.of(), Map.of());
        assertTrue(prompt.contains("Upbeat synthwave for a tech launch."));
    }

    @Test
    void audioPackMusicPrompt_generatesDefaultDirectionWhenNoPromptProvided() {
        Map<String, Object> run = Map.of("title", "Widget Launch Ad");
        String prompt = assembler.audioPackMusicPrompt(Map.of(), Map.of(), Map.of(), run);
        assertTrue(prompt.contains("Widget Launch Ad") || prompt.contains("modern commercial background score"));
        assertTrue(prompt.contains("No vocals, no lyrics, no spoken dialogue, no narration."));
    }

    @Test
    void audioPackMusicPrompt_includesCharacterContextWhenCastPresent() {
        Map<String, Object> run = Map.of(
                "storyCharacters", List.of(Map.of("name", "Maya", "gender", "female", "role", "lead"))
        );
        String prompt = assembler.audioPackMusicPrompt(Map.of(), Map.of(), Map.of(), run);
        assertTrue(prompt.contains("Maya"));
    }

    @Test
    void audioMixStandards_returnsDefaultsAndAppliesOverridesInOrder() {
        Map<String, Object> defaults = assembler.audioMixStandards();
        assertEquals("consistent_speech_first", defaults.get("dialogueLevel"));
        assertEquals(-3, defaults.get("dialogueTargetDb"));

        Map<String, Object> withOverride = assembler.audioMixStandards(Map.of("dialogueTargetDb", -6));
        assertEquals(-6, withOverride.get("dialogueTargetDb"));
        assertEquals("duck_under_speech", withOverride.get("backgroundMusicDucking"));
    }

    @Test
    void audioMixStandards_ignoresBlankOverrides() {
        Map<String, Object> result = assembler.audioMixStandards((Object) null, Map.of());
        assertEquals("consistent_speech_first", result.get("dialogueLevel"));
    }

    @Test
    void withAudioMixStandards_addsStandardsAndPolicyToPlan() {
        Map<String, Object> plan = assembler.withAudioMixStandards(Map.of("mood", "confident"));
        assertEquals("confident", plan.get("mood"));
        assertTrue(plan.get("audioMixStandards") instanceof Map);
        assertEquals("dialogue_first_music_ducked_room_tone_sparse_sfx_scene_reverb_smooth_fades", plan.get("audioProductionPolicy"));
    }

    @Test
    void withAudioMixStandards_handlesNullPlan() {
        Map<String, Object> plan = assembler.withAudioMixStandards(null);
        assertTrue(plan.containsKey("audioMixStandards"));
    }
}
