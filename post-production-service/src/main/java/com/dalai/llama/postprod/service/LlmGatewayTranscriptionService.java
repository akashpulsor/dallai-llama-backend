package com.dalai.llama.postprod.service;

import com.dalai.llama.postprod.service.llmgateway.LlmGatewayChatRequest;
import com.dalai.llama.postprod.service.llmgateway.LlmGatewayChatRequest.LlmGatewayMessage;
import com.dalai.llama.postprod.service.llmgateway.LlmGatewayChatResponse;
import com.dalai.llama.postprod.service.llmgateway.LlmGatewayClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Same fal.ai dispatch + named model-shaping-verification gap as the other LlmGateway*Service
 * classes. Routes to model_id=transcription-v1 (type=transcription) by default. */
@Service
public class LlmGatewayTranscriptionService implements TranscriptionService {

    private final LlmGatewayClient llmGatewayClient;
    private final String defaultModel;

    public LlmGatewayTranscriptionService(
            LlmGatewayClient llmGatewayClient,
            @Value("${post-production.dubbing.default-transcription-model}") String defaultModel
    ) {
        this.llmGatewayClient = llmGatewayClient;
        this.defaultModel = defaultModel;
    }

    @Override
    public TranscriptionResult transcribe(UUID tenantId, String idempotencyKey, String sourceVideoUrl, String modelOverride) {
        if (sourceVideoUrl == null || sourceVideoUrl.isBlank()) {
            throw PostProductionException.badRequest("No source video to transcribe");
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("source_video_url", sourceVideoUrl);

        String modelId = (modelOverride == null || modelOverride.isBlank()) ? defaultModel : modelOverride;
        LlmGatewayChatResponse response = llmGatewayClient.chat(
                tenantId.toString(),
                idempotencyKey,
                new LlmGatewayChatRequest(modelId, List.of(new LlmGatewayMessage("user", "")), params, null, null)
        );
        if (response == null || response.response() == null || response.response().isBlank()) {
            throw PostProductionException.upstream("llm-gateway returned no transcript");
        }
        return new TranscriptionResult(response.response());
    }
}
