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

/** Same pattern/caveat as LlmGatewayFoleyGenerationService. Routes to model_id=music-gen-v1
 * (type=music) by default. */
@Service
public class LlmGatewayMusicGenerationService implements MusicGenerationService {

    private final LlmGatewayClient llmGatewayClient;
    private final String defaultModel;

    public LlmGatewayMusicGenerationService(
            LlmGatewayClient llmGatewayClient,
            @Value("${post-production.llm-gateway.default-music-model}") String defaultModel
    ) {
        this.llmGatewayClient = llmGatewayClient;
        this.defaultModel = defaultModel;
    }

    @Override
    public AudioGenerationResult generateMusic(UUID tenantId, String idempotencyKey, String moodPrompt, Integer durationSeconds, String modelOverride) {
        if (moodPrompt == null || moodPrompt.isBlank()) {
            throw PostProductionException.badRequest("No mood/style prompt to generate music from");
        }
        Map<String, Object> params = new LinkedHashMap<>();
        if (durationSeconds != null) {
            params.put("duration_seconds", durationSeconds);
        }

        String modelId = (modelOverride == null || modelOverride.isBlank()) ? defaultModel : modelOverride;
        LlmGatewayChatResponse response = llmGatewayClient.chat(
                tenantId.toString(),
                idempotencyKey,
                new LlmGatewayChatRequest(modelId, List.of(new LlmGatewayMessage("user", moodPrompt)), params, null, null)
        );
        if (response == null || response.response() == null || response.response().isBlank()) {
            throw PostProductionException.upstream("llm-gateway returned no music audio URL");
        }
        BigDecimal cost = response.usage() == null ? BigDecimal.ZERO : response.usage().cost();
        return new AudioGenerationResult(response.jobId(), response.response(), cost);
    }
}
