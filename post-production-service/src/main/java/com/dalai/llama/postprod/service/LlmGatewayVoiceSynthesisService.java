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

/** Same fal.ai meta-provider dispatch + named model_master/FalAiProvider-shaping-verification gap
 * as VoiceCloneGenerationService/LipSyncGenerationService -- see their class comments. Routes to
 * {@code model_id=tts-v1} (type=tts, seeded in llm-gateway's V15 migration). */
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
    public VoiceSynthesisResult synthesize(UUID tenantId, String idempotencyKey, String providerVoiceId, String text, String language, String modelOverride) {
        if (providerVoiceId == null || providerVoiceId.isBlank()) {
            throw PostProductionException.badRequest("No cloned voice id to synthesize speech with");
        }
        if (text == null || text.isBlank()) {
            throw PostProductionException.badRequest("No dialogue text to synthesize");
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("voice_id", providerVoiceId);
        params.put("language", language);

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
