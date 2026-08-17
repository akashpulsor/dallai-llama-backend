package com.dalai.llama.creator.service;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Characterization test for the captionsEnabled field added to
 * ProviderRequestBuilder.resolveSeedAndNegativePrompt() - pinned before that method changes, per
 * the prompt-builder priority fix plan. Zero prior coverage existed for ProviderRequestBuilder;
 * this deliberately stays narrow (one field) rather than attempting full-class characterization
 * in the same pass as a behavior change.
 */
class ProviderRequestBuilderTest {

    private final ScreenplayVideoService owner = mock(ScreenplayVideoService.class);

    {
        when(owner.dialogueTextForScene(any())).thenReturn("");
    }

    @Test
    void captionsEnabled_defaultsTrue_whenAbsentFromRequest() {
        Map<String, Object> providerRequest = build(Map.of());
        assertEquals(true, providerRequest.get("captionsEnabled"));
    }

    @Test
    void captionsEnabled_passesThroughExplicitFalse() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("captionsEnabled", false);
        Map<String, Object> providerRequest = build(request);
        assertEquals(false, providerRequest.get("captionsEnabled"));
    }

    @Test
    void captionsEnabled_passesThroughExplicitTrue() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("captionsEnabled", true);
        Map<String, Object> providerRequest = build(request);
        assertEquals(true, providerRequest.get("captionsEnabled"));
    }

    private Map<String, Object> build(Map<String, Object> request) {
        Map<String, Object> scene = Map.of("id", "scene-1", "action", "Wide shot of the product.");
        Map<String, Object> contextPayload = Map.of();
        ProviderRequestBuilder builder = new ProviderRequestBuilder(
                owner, "seedance", "bytedance/seedance-2.0", scene, request, contextPayload
        );
        return builder.build();
    }
}
