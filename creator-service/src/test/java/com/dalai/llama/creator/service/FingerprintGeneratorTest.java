package com.dalai.llama.creator.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Characterization tests for FingerprintGenerator - the extraction of
 * ScreenplayVideoService's SHA-256 fingerprinting cluster.
 */
class FingerprintGeneratorTest {

    private final FingerprintGenerator generator = new FingerprintGenerator();

    @Test
    void stableFingerprint_isDeterministicForSameInput() {
        assertEquals(generator.stableFingerprint("a", "b", "c"), generator.stableFingerprint("a", "b", "c"));
    }

    @Test
    void stableFingerprint_differsForDifferentInput() {
        assertNotEquals(generator.stableFingerprint("a", "b"), generator.stableFingerprint("a", "c"));
    }

    @Test
    void stableFingerprint_handlesNullPartsArrayWithoutThrowing() {
        String fingerprint = generator.stableFingerprint((String[]) null);
        assertFalse(fingerprint.isBlank());
    }

    @Test
    void dialogueFingerprint_isDeterministicForSameInput() {
        Map<String, Object> inputPayload = Map.of("voiceProvider", "google_chirp", "voiceName", "voice-1");
        Map<String, Object> run = Map.of("languageCode", "hi-IN");
        assertEquals(
                generator.dialogueFingerprint("Hello there", inputPayload, run),
                generator.dialogueFingerprint("Hello there", inputPayload, run)
        );
    }

    @Test
    void dialogueFingerprint_differsWhenVoiceTextDiffers() {
        Map<String, Object> inputPayload = Map.of();
        Map<String, Object> run = Map.of();
        assertNotEquals(
                generator.dialogueFingerprint("Hello there", inputPayload, run),
                generator.dialogueFingerprint("Goodbye", inputPayload, run)
        );
    }

    @Test
    void founderEmbeddingIds_derivesAllFourIdsFromSameSuffixAndFixedVersion() {
        Map<String, Object> ids = generator.founderEmbeddingIds("abc123fingerprint");
        String suffix = "abc123fingerprint".substring(0, 16);
        assertEquals("portrait-" + suffix, ids.get("portraitEmbeddingId"));
        assertEquals("face-" + suffix, ids.get("facialFeatureEmbeddingId"));
        assertEquals("voice-" + suffix, ids.get("voiceEmbeddingId"));
        assertEquals("founder-kit-v1", ids.get("identityEmbeddingVersion"));
    }

    @Test
    void founderEmbeddingIds_generatesRandomFingerprintWhenBlank() {
        Map<String, Object> ids = generator.founderEmbeddingIds("");
        assertTrue(((String) ids.get("portraitEmbeddingId")).startsWith("portrait-"));
    }
}
