package com.dalai.llama.creator.service.screenplayvideo;

import java.util.Map;

/**
 * Narrow seam for building the provider-agnostic providerRequest map for a scene. Both
 * SceneChatEditor and DialogueVoiceCloner need this (it resolves provider/model then delegates to
 * ProviderRequestBuilder), which otherwise lives deep inside ScreenplayVideoService alongside
 * cast-mapping/product-reference selection shared with plain scene generation. Depending on this
 * interface instead of reaching back into the god class via package-private access makes the
 * dependency explicit and mockable in tests.
 */
public interface ProviderRequestFactory {

    Map<String, Object> buildProviderRequest(Map<String, Object> run, Map<String, Object> scene, Map<String, Object> request);
}
