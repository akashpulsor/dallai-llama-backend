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

/** Same fal.ai meta-provider dispatch as the other LlmGateway*GenerationService classes -- see
 * LlmGatewayFoleyGenerationService's class comment. Routes to model_id=fal-ai/topaz/upscale/video
 * (type=upscale) by default; a caller may override with any other registered type=upscale model
 * id (GET /v1/post-production/models?type=upscale). */
@Service
public class LlmGatewayUpscaleGenerationService implements UpscaleGenerationService {

    private final LlmGatewayClient llmGatewayClient;
    private final String defaultModel;

    public LlmGatewayUpscaleGenerationService(
            LlmGatewayClient llmGatewayClient,
            @Value("${post-production.llm-gateway.default-upscale-model}") String defaultModel
    ) {
        this.llmGatewayClient = llmGatewayClient;
        this.defaultModel = defaultModel;
    }

    @Override
    public UpscaleGenerationResult upscale(UUID tenantId, String idempotencyKey, String sourceVideoUrl, String modelOverride, Double durationSeconds) {
        if (sourceVideoUrl == null || sourceVideoUrl.isBlank()) {
            throw PostProductionException.badRequest("No source video to upscale");
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("source_video_url", sourceVideoUrl);
        // Required for llm-gateway's duration-priced billing (LlmGatewayService.computeCost) --
        // without it the call still upscales fine, it just bills $0, same silent-gap this whole
        // change closes for Wan 3.0/Topaz. See GenerateUpscaleRequest's javadoc for why this can't
        // be derived server-side instead.
        if (durationSeconds != null) {
            params.put("duration_seconds", durationSeconds);
        }

        String modelId = (modelOverride == null || modelOverride.isBlank()) ? defaultModel : modelOverride;
        LlmGatewayChatResponse response = llmGatewayClient.chat(
                tenantId.toString(),
                idempotencyKey,
                new LlmGatewayChatRequest(modelId, List.of(new LlmGatewayMessage("user", "Upscale this video.")), params, null, null)
        );
        if (response == null || response.response() == null || response.response().isBlank()) {
            throw PostProductionException.upstream("llm-gateway returned no upscaled video URL");
        }
        BigDecimal cost = response.usage() == null ? BigDecimal.ZERO : response.usage().cost();
        return new UpscaleGenerationResult(response.jobId(), response.response(), cost);
    }
}
