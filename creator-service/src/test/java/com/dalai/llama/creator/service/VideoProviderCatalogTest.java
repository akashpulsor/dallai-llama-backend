package com.dalai.llama.creator.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Characterization tests for VideoProviderCatalog - the extraction of ScreenplayVideoService's
 * provider/model catalog cluster (which providers/models exist, their capability limits, and
 * their rate-limit policy).
 */
class VideoProviderCatalogTest {

    private final VideoProviderCatalog catalog = new VideoProviderCatalog();

    @Test
    void normalizeVideoProvider_collapsesKnownSynonymsAndDefaultsToGeminiOmni() {
        assertEquals("gemini_omni", catalog.normalizeVideoProvider(null));
        assertEquals("gemini_omni", catalog.normalizeVideoProvider("google-omni"));
        assertEquals("seedance", catalog.normalizeVideoProvider("fal_seedance"));
        assertEquals("synthesia", catalog.normalizeVideoProvider("synthesia_api"));
        assertEquals("dalai_llama", catalog.normalizeVideoProvider("local-avatar"));
        assertEquals("omini", catalog.normalizeVideoProvider("openai_omni"));
    }

    @Test
    void normalizeVideoProvider_veoSynonymsResolveToDefaultProviderNotGoogleVeo() {
        // Characterizes existing (surprising) behavior: this branch returns
        // DEFAULT_SCREENPLAY_VIDEO_PROVIDER ("gemini_omni") instead of "google_veo", unlike every
        // other branch which returns its own bucket's literal. Preserved as-is since this is a
        // pure extraction - flagged separately as a likely pre-existing bug, not fixed here.
        assertEquals("gemini_omni", catalog.normalizeVideoProvider("veo"));
        assertEquals("gemini_omni", catalog.normalizeVideoProvider("google_veo"));
        assertEquals("gemini_omni", catalog.normalizeVideoProvider("vertex_veo"));
    }

    @Test
    void normalizeVideoProvider_passesThroughUnknownNonBlankValue() {
        assertEquals("some_new_provider", catalog.normalizeVideoProvider("some_new_provider"));
    }

    @Test
    void modelForProvider_returnsRequestedModelWhenCompatible() {
        assertEquals("bytedance/seedance-2.0/fast", catalog.modelForProvider("seedance", "bytedance/seedance-2.0/fast"));
    }

    @Test
    void modelForProvider_veoRequestedModelIsTreatedIncompatibleDueToNormalizeVideoProviderBehavior() {
        // Because normalizeVideoProvider("google_veo") resolves to "gemini_omni" (see
        // normalizeVideoProvider_veoSynonymsResolveToDefaultProviderNotGoogleVeo), a requested Veo
        // model is never recognized as compatible, so this always falls back to the provider
        // default rather than honoring the requested model - characterizing existing behavior.
        assertEquals("veo-3.1-generate-preview", catalog.modelForProvider("google_veo", "veo-3.1-fast-generate-preview"));
    }

    @Test
    void modelForProvider_fallsBackToDefaultWhenRequestedModelIncompatible() {
        assertEquals("synthesia-avatar-video", catalog.modelForProvider("synthesia", "totally-unrelated-model-xyz"));
        assertEquals("synthesia-avatar-video", catalog.modelForProvider("synthesia", null));
    }

    @Test
    void modelForProvider_defaultsToSeedanceForUnknownProvider() {
        assertEquals("bytedance/seedance-2.0", catalog.modelForProvider("unknown_provider", null));
    }

    @Test
    void defaultMaxClipSecondsForProvider_returnsProviderSpecificDefaults() {
        assertEquals(8, catalog.defaultMaxClipSecondsForProvider("google_veo"));
        assertEquals(10, catalog.defaultMaxClipSecondsForProvider("gemini_omni"));
        assertEquals(15, catalog.defaultMaxClipSecondsForProvider("omini"));
        assertEquals(20, catalog.defaultMaxClipSecondsForProvider("synthesia"));
        assertEquals(8, catalog.defaultMaxClipSecondsForProvider("dalai_llama"));
        assertEquals(15, catalog.defaultMaxClipSecondsForProvider("seedance"));
    }

    @Test
    void defaultMaxClipSecondsForProvider_modelNameContainingLongOverridesTo20() {
        assertEquals(20, catalog.defaultMaxClipSecondsForProvider("seedance", "some-model-long-form"));
    }

    @Test
    void modelCapabilityMaxClipSeconds_clampsRequestedValueToProviderMax() {
        assertEquals(1, catalog.modelCapabilityMaxClipSeconds("google_veo", "veo-3.1", 0));
        assertEquals(5, catalog.modelCapabilityMaxClipSeconds("seedance", "bytedance/seedance-2.0", 5));
    }

    @Test
    void rateLimitPolicy_includesMaxClipSecondsAndStrategy() {
        Map<String, Object> policy = catalog.rateLimitPolicy("synthesia");
        assertEquals(20, policy.get("maxClipSeconds"));
        assertEquals("queue_scene_requests_and_poll_provider_operation", policy.get("strategy"));
        assertTrue(policy.containsKey("maxConcurrentGenerations"));
        assertTrue(policy.containsKey("pollIntervalMs"));
        assertTrue(policy.containsKey("timeoutMs"));
    }

    @Test
    void rateLimitPolicy_unknownProviderFallsBackToSeedanceDefaults() {
        Map<String, Object> policy = catalog.rateLimitPolicy("totally_unknown");
        assertEquals(15, policy.get("maxClipSeconds"));
    }

    @Test
    void providerOptions_listsThreeOptionsAllTaggedVideoMediaType() {
        List<Map<String, Object>> options = catalog.providerOptions();
        assertEquals(3, options.size());
        for (Map<String, Object> option : options) {
            assertEquals("video", option.get("mediaType"));
        }
    }

    @Test
    void modelOptions_returnsProviderSpecificModelsAndDefaultsToSeedance() {
        assertEquals(2, catalog.modelOptions("google_veo").size());
        assertEquals(1, catalog.modelOptions("gemini_omni").size());
        assertEquals(2, catalog.modelOptions("omini").size());
        assertFalse(catalog.modelOptions("anything_else").isEmpty());
    }

    @Test
    void googleVeoBaseUrl_defaultsToAiStudioEndpointWhenNoExplicitOverrideOrVertexConfig() {
        String url = catalog.googleVeoBaseUrl("veo-3.1-generate-preview");
        assertFalse(url.isBlank());
    }

    @Test
    void providerLabel_mapsKnownProvidersAndDefaultsToSeedance() {
        assertEquals("Google Veo", catalog.providerLabel("google_veo"));
        assertEquals("Gemini Omni Flash", catalog.providerLabel("gemini_omni"));
        assertEquals("Synthesia", catalog.providerLabel("synthesia"));
        assertEquals("Dalai Llama local", catalog.providerLabel("dalai_llama"));
        assertEquals("Omini", catalog.providerLabel("omini"));
        assertEquals("Seedance", catalog.providerLabel("seedance"));
        assertEquals("Seedance", catalog.providerLabel("unknown_provider"));
    }
}
