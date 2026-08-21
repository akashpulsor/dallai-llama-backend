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

/** Same fal.ai meta-provider dispatch + named model-shaping-verification gap as the other
 * LlmGateway*GenerationService classes -- see VoiceCloneGenerationService's class comment.
 * Routes to model_id=foley-v1 (type=foley) by default. */
@Service
public class LlmGatewayFoleyGenerationService implements FoleyGenerationService {

    private final LlmGatewayClient llmGatewayClient;
    private final String defaultModel;

    public LlmGatewayFoleyGenerationService(
            LlmGatewayClient llmGatewayClient,
            @Value("${post-production.llm-gateway.default-foley-model}") String defaultModel
    ) {
        this.llmGatewayClient = llmGatewayClient;
        this.defaultModel = defaultModel;
    }

    @Override
    public AudioGenerationResult generateFoley(UUID tenantId, String idempotencyKey, String sourceVideoUrl, String cueDescription, String modelOverride) {
        if (cueDescription == null || cueDescription.isBlank()) {
            throw PostProductionException.badRequest("No foley cue description to generate from");
        }
        Map<String, Object> params = new LinkedHashMap<>();
        if (sourceVideoUrl != null && !sourceVideoUrl.isBlank()) {
            params.put("source_video_url", sourceVideoUrl);
        }

        String modelId = (modelOverride == null || modelOverride.isBlank()) ? defaultModel : modelOverride;
        LlmGatewayChatResponse response = llmGatewayClient.chat(
                tenantId.toString(),
                idempotencyKey,
                new LlmGatewayChatRequest(modelId, List.of(new LlmGatewayMessage("user", cueDescription)), params, null, null)
        );
        if (response == null || response.response() == null || response.response().isBlank()) {
            throw PostProductionException.upstream("llm-gateway returned no foley audio URL");
        }
        BigDecimal cost = response.usage() == null ? BigDecimal.ZERO : response.usage().cost();
        return new AudioGenerationResult(response.jobId(), response.response(), cost);
    }
}
