package com.dalai.llama.postprod.service;

import com.dalai.llama.postprod.service.llmgateway.LlmGatewayChatRequest;
import com.dalai.llama.postprod.service.llmgateway.LlmGatewayChatRequest.LlmGatewayMessage;
import com.dalai.llama.postprod.service.llmgateway.LlmGatewayChatResponse;
import com.dalai.llama.postprod.service.llmgateway.LlmGatewayClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Dispatches through llm-gateway's fal.ai meta-provider adapter, same principle as
 * video-generation-service's own LlmGatewayVideoGenDispatchService -- this service never talks to
 * fal.ai directly.
 *
 * <p><b>Known gap, named not hidden:</b> {@code model_id=voice-clone-v1} is not yet seeded into
 * llm-gateway's model_master table, and FalAiProvider's own request-shaping
 * (toFalRequestBody/falEndpoint) is currently Seedance/video-specific -- it doesn't know how to
 * build a voice-clone payload (reference audio in, cloned-voice-id out) yet. This mirrors exactly
 * the state seedance-v1 was in before Phase A of llm-gateway's build: the call path, job
 * lifecycle, and billing plumbing are all real and correct, but a real dispatch will 404 until
 * both the model_master seed row and FalAiProvider's request-shaping are extended for this model
 * type -- a bounded, named follow-up, not silently swept under.
 */
@Service
public class LlmGatewayVoiceCloneGenerationService implements VoiceCloneGenerationService {

    private final LlmGatewayClient llmGatewayClient;
    private final String defaultModel;

    public LlmGatewayVoiceCloneGenerationService(
            LlmGatewayClient llmGatewayClient,
            @Value("${post-production.llm-gateway.default-voice-clone-model}") String defaultModel
    ) {
        this.llmGatewayClient = llmGatewayClient;
        this.defaultModel = defaultModel;
    }

    @Override
    public VoiceCloneResult cloneVoice(UUID tenantId, String idempotencyKey, String referenceAudioUrl, String targetLanguage, String modelOverride) {
        if (referenceAudioUrl == null || referenceAudioUrl.isBlank()) {
            throw PostProductionException.badRequest("No reference audio available to clone a voice from");
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("reference_audio_url", referenceAudioUrl);
        params.put("target_language", targetLanguage);

        String modelId = (modelOverride == null || modelOverride.isBlank()) ? defaultModel : modelOverride;
        LlmGatewayChatResponse response = llmGatewayClient.chat(
                tenantId.toString(),
                idempotencyKey,
                new LlmGatewayChatRequest(modelId, List.of(new LlmGatewayMessage("user", "")), params, null, null)
        );
        if (response == null || response.response() == null || response.response().isBlank()) {
            throw PostProductionException.upstream("llm-gateway returned no cloned voice id");
        }
        BigDecimal cost = response.usage() == null ? BigDecimal.ZERO : response.usage().cost();
        // response() carries the provider's cloned-voice-id as its content, the same convention
        // FalAiProvider uses for a video job's output URI -- one string result field for whatever
        // the model produces, typed downstream by the caller who knows what model it asked for.
        return new VoiceCloneResult(response.jobId(), "fal.ai", response.response(), cost);
    }
}
