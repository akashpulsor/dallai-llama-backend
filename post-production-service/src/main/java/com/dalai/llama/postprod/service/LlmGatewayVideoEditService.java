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

/** Same fal.ai dispatch + named model-shaping-verification gap as the other LlmGateway*Service
 * classes. Routes to model_id=kling-edit-v1 (type=video_edit) by default. */
@Service
public class LlmGatewayVideoEditService implements VideoEditService {

    private final LlmGatewayClient llmGatewayClient;
    private final String defaultModel;

    public LlmGatewayVideoEditService(
            LlmGatewayClient llmGatewayClient,
            @Value("${post-production.llm-gateway.default-video-edit-model}") String defaultModel
    ) {
        this.llmGatewayClient = llmGatewayClient;
        this.defaultModel = defaultModel;
    }

    @Override
    public VideoEditResult editVideo(
            UUID tenantId, String idempotencyKey, String sourceVideoUrl,
            Integer startSeconds, Integer endSeconds,
            String editInstruction, String referenceImageUrl, String modelOverride
    ) {
        if (sourceVideoUrl == null || sourceVideoUrl.isBlank()) {
            throw PostProductionException.badRequest("No source video to edit");
        }
        if (editInstruction == null || editInstruction.isBlank()) {
            throw PostProductionException.badRequest("No edit instruction given");
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("source_video_url", sourceVideoUrl);
        if (startSeconds != null) {
            params.put("start_seconds", startSeconds);
        }
        if (endSeconds != null) {
            params.put("end_seconds", endSeconds);
        }
        if (referenceImageUrl != null && !referenceImageUrl.isBlank()) {
            params.put("reference_image_url", referenceImageUrl);
        }

        String modelId = (modelOverride == null || modelOverride.isBlank()) ? defaultModel : modelOverride;
        LlmGatewayChatResponse response = llmGatewayClient.chat(
                tenantId.toString(),
                idempotencyKey,
                new LlmGatewayChatRequest(modelId, List.of(new LlmGatewayMessage("user", editInstruction)), params, null, null)
        );
        if (response == null || response.response() == null || response.response().isBlank()) {
            throw PostProductionException.upstream("llm-gateway returned no edited video URL");
        }
        BigDecimal cost = response.usage() == null ? BigDecimal.ZERO : response.usage().cost();
        return new VideoEditResult(response.jobId(), response.response(), cost);
    }
}
