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

/** Dispatches through llm-gateway's fal.ai meta-provider to fal-ai/minimax/voice-clone -- the
 * same real, verified model VoiceCloneGenerationService uses, since MiniMax fuses cloning and
 * synthesis into one endpoint (see VoiceSynthesisService's own javadoc). Passing an explicit
 * {@code text} param (not left to a chat-message default) is what tells FalAiProvider to read
 * back the synthesized {@code audio.url} instead of the bare {@code custom_voice_id}. */
@Service
public class LlmGatewayVoiceSynthesisService implements VoiceSynthesisService {

    private final LlmGatewayClient llmGatewayClient;
    private final String defaultModel;

    public LlmGatewayVoiceSynthesisService(
            LlmGatewayClient llmGatewayClient,
            @Value("${post-production.llm-gateway.default-tts-model}") String defaultModel
    ) {
        this.llmGatewayClient = llmGatewayClient;
        this.defaultModel = defaultModel;
    }

    @Override
    public VoiceSynthesisResult synthesize(UUID tenantId, String idempotencyKey, String providerVoiceId, String referenceAudioUrl, String text, String language, String modelOverride) {
        if (referenceAudioUrl == null || referenceAudioUrl.isBlank()) {
            throw PostProductionException.badRequest("No reference audio available to synthesize this cloned voice's speech");
        }
        if (text == null || text.isBlank()) {
            throw PostProductionException.badRequest("No dialogue text to synthesize");
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("reference_audio_url", referenceAudioUrl);
        params.put("text", text);

        String modelId = (modelOverride == null || modelOverride.isBlank()) ? defaultModel : modelOverride;
        LlmGatewayChatResponse response = llmGatewayClient.chat(
                tenantId.toString(),
                idempotencyKey,
                new LlmGatewayChatRequest(modelId, List.of(new LlmGatewayMessage("user", text)), params, null, null)
        );
        if (response == null || response.response() == null || response.response().isBlank()) {
            throw PostProductionException.upstream("llm-gateway returned no synthesized audio URL");
        }
        BigDecimal cost = response.usage() == null ? BigDecimal.ZERO : response.usage().cost();
        return new VoiceSynthesisResult(response.jobId(), response.response(), cost);
    }
}
