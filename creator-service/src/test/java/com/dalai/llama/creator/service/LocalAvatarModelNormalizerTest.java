package com.dalai.llama.creator.service;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Characterization tests for LocalAvatarModelNormalizer - the extraction of
 * ScreenplayVideoService's local/open-source founder-avatar model normalization cluster.
 */
class LocalAvatarModelNormalizerTest {

    private final LocalAvatarModelNormalizer normalizer = new LocalAvatarModelNormalizer();

    @Test
    void normalizeLocalVoiceModel_collapsesKnownSynonymsAndDefaultsToMinimax() {
        assertEquals("fal_minimax_voice_clone", normalizer.normalizeLocalVoiceModel(null));
        assertEquals("client_rvc_english", normalizer.normalizeLocalVoiceModel("founder_female_v1"));
        assertEquals("fal_chatterbox_multilingual", normalizer.normalizeLocalVoiceModel("chatterbox"));
        assertEquals("elevenlabs_v3_voice_clone", normalizer.normalizeLocalVoiceModel("eleven_v3"));
        assertEquals("sarvam_voice_clone", normalizer.normalizeLocalVoiceModel("sarvam-clone"));
        assertEquals("elevenlabs_professional", normalizer.normalizeLocalVoiceModel("11labs"));
        assertEquals("uploaded_founder_audio", normalizer.normalizeLocalVoiceModel("exact-upload"));
        assertEquals("synthesia_managed", normalizer.normalizeLocalVoiceModel("synthesia"));
        assertEquals("api_fallback", normalizer.normalizeLocalVoiceModel("proprietary-fallback"));
    }

    @Test
    void requireSceneVoiceMethod_acceptsAllowlistedMethodAndDefaultsToMinimax() {
        assertEquals("sarvam_voice_clone", normalizer.requireSceneVoiceMethod("sarvam_voice_clone"));
        assertEquals("fal_minimax_voice_clone", normalizer.requireSceneVoiceMethod(null));
    }

    @Test
    void requireSceneVoiceMethod_rejectsUnsupportedMethod() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> normalizer.requireSceneVoiceMethod("not_a_real_method"));
        assertTrue(ex.getReason().contains("Unsupported scene voice clone method"));
    }

    @Test
    void providerVoiceIdForMethod_prefersMethodSpecificProfileFieldOverGenericFallback() {
        Map<String, Object> profile = Map.of(
                "minimaxVoiceId", "minimax-id-1",
                "providerVoiceId", "generic-id",
                "localModels", Map.of("voiceModel", "fal_minimax_voice_clone")
        );
        assertEquals("minimax-id-1", normalizer.providerVoiceIdForMethod("fal_minimax_voice_clone", profile));
    }

    @Test
    void providerVoiceIdForMethod_fallsBackToGenericProviderVoiceIdOnlyWhenSelectedProfileMethod() {
        Map<String, Object> profile = Map.of(
                "providerVoiceId", "generic-id",
                "localModels", Map.of("voiceModel", "fal_elevenlabs_v3")
        );
        assertEquals("generic-id", normalizer.providerVoiceIdForMethod("fal_elevenlabs_v3", profile));
        assertEquals("", normalizer.providerVoiceIdForMethod("sarvam_voice_clone", profile));
    }

    @Test
    void providerVoiceIdForMethod_unknownMethodReturnsEmpty() {
        assertEquals("", normalizer.providerVoiceIdForMethod("some_unknown_method", Map.of()));
    }

    @Test
    void applyPronunciationGuide_substitutesTermsCaseInsensitivelyOnWordBoundaries() {
        String result = normalizer.applyPronunciationGuide("Dalai Llama makes videos.", "Dalai Llama => DAH-lai LAH-ma");
        assertEquals("DAH-lai LAH-ma makes videos.", result);
    }

    @Test
    void applyPronunciationGuide_returnsTextUnchangedWhenGuideBlank() {
        assertEquals("Hello world", normalizer.applyPronunciationGuide("Hello world", ""));
        assertEquals("Hello world", normalizer.applyPronunciationGuide("Hello world", null));
    }

    @Test
    void applyPronunciationGuide_ignoresMalformedLinesWithoutSeparator() {
        assertEquals("Hello world", normalizer.applyPronunciationGuide("Hello world", "not-a-valid-rule-line"));
    }

    @Test
    void normalizeLocalTalkingAvatarModel_collapsesKnownSynonymsAndDefaultsToSourceVideo() {
        assertEquals("fal_heygen_avatar4", normalizer.normalizeLocalTalkingAvatarModel("heygen-avatar-4"));
        assertEquals("fal_happy_horse_v1_1", normalizer.normalizeLocalTalkingAvatarModel("happy_horse"));
        assertEquals("source_video", normalizer.normalizeLocalTalkingAvatarModel("unknown-model"));
        // null input defaults to "fal_heygen_avatar4" (the method's own default text), which then
        // matches the heygen/avatar4 branch - so null does NOT fall through to "source_video".
        assertEquals("fal_heygen_avatar4", normalizer.normalizeLocalTalkingAvatarModel(null));
    }

    @Test
    void normalizeLocalLipSyncModel_collapsesKnownSynonymsAndDefaultsToLatentsync() {
        assertEquals("avatar_native", normalizer.normalizeLocalLipSyncModel("native"));
        assertEquals("fal_musetalk", normalizer.normalizeLocalLipSyncModel("musetalk"));
        assertEquals("fal_latentsync", normalizer.normalizeLocalLipSyncModel("fal-latent-sync"));
        assertEquals("sync_labs", normalizer.normalizeLocalLipSyncModel("synclabs"));
        assertEquals("fal_latentsync", normalizer.normalizeLocalLipSyncModel(null));
    }

    @Test
    void normalizeLocalImageModel_collapsesKnownSynonymsAndDefaultsToFlux() {
        assertEquals("gemini_storyboard", normalizer.normalizeLocalImageModel(null));
        assertEquals("ic_lightning", normalizer.normalizeLocalImageModel("ic-light"));
        assertEquals("flux_1_dev", normalizer.normalizeLocalImageModel("something-else"));
    }

    @Test
    void normalizeLocalVideoModel_alwaysReturnsFalSeedance() {
        assertEquals("fal_seedance", normalizer.normalizeLocalVideoModel(null));
        assertEquals("fal_seedance", normalizer.normalizeLocalVideoModel("anything"));
    }
}
