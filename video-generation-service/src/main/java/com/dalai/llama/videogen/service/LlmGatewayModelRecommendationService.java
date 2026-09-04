package com.dalai.llama.videogen.service;

import com.dalai.llama.videogen.service.llmgateway.LlmGatewayChatRequest;
import com.dalai.llama.videogen.service.llmgateway.LlmGatewayChatRequest.LlmGatewayMessage;
import com.dalai.llama.videogen.service.llmgateway.LlmGatewayChatResponse;
import com.dalai.llama.videogen.service.llmgateway.LlmGatewayClient;
import com.dalai.llama.videogen.service.llmgateway.LlmGatewayModelSummary;
import com.dalai.llama.videogen.web.TenantContextHolder;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Doc §21 (revised): no admin-tunable model_capability_signature scoring table -- one llm-gateway
 * call, reasoning done live by the model. The heuristic (faces/emotion -> premium model,
 * pure motion -> cheaper model) lives in llm-gateway's MODEL_RECOMMENDATION prompt_template, not
 * here.
 */
@Slf4j
@Service
public class LlmGatewayModelRecommendationService implements ModelRecommendationService {

    private final LlmGatewayClient llmGatewayClient;
    private final ObjectMapper objectMapper;
    private final String defaultModel;

    public LlmGatewayModelRecommendationService(
            LlmGatewayClient llmGatewayClient,
            ObjectMapper objectMapper,
            @Value("${video-gen.llm-gateway.default-compression-model}") String defaultModel
    ) {
        this.llmGatewayClient = llmGatewayClient;
        this.objectMapper = objectMapper;
        this.defaultModel = defaultModel;
    }

    @Override
    public ModelRecommendation recommend(ShotSignature shotSignature) {
        UUID tenantId = TenantContextHolder.get().tenantId();
        List<LlmGatewayModelSummary> videoModels;
        try {
            videoModels = llmGatewayClient.listModels(tenantId.toString(), "video");
        } catch (RuntimeException ex) {
            log.warn("Could not fetch video model catalog for recommendation, skipping: {}", ex.getMessage());
            return null;
        }
        if (videoModels == null || videoModels.isEmpty()) {
            return null;
        }

        String userMessage = "Shot signature: hasFace=%s, isMotionOnly=%s, requiresLipSync=%s, hasDialogueBeats=%s, durationBucket=%s, qualityTier=%s%n"
                .formatted(shotSignature.hasFace(), shotSignature.isMotionOnly(), shotSignature.requiresLipSync(),
                        shotSignature.hasDialogueBeats(), shotSignature.durationBucket(), shotSignature.qualityTier())
                + "Available models: " + videoModels.stream().map(LlmGatewayModelSummary::modelId).toList();

        LlmGatewayChatResponse response;
        try {
            response = llmGatewayClient.chat(
                    tenantId.toString(),
                    "model-recommendation-" + UUID.randomUUID(),
                    new LlmGatewayChatRequest(
                            defaultModel,
                            List.of(new LlmGatewayMessage("user", userMessage)),
                            Map.of(),
                            "MODEL_RECOMMENDATION",
                            Map.of()
                    )
            );
        } catch (RuntimeException ex) {
            log.warn("Model recommendation call failed, caller falls back to project/config default: {}", ex.getMessage());
            return null;
        }
        return parseRecommendation(response == null ? null : response.response());
    }

    private ModelRecommendation parseRecommendation(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(json, Map.class);
            return new ModelRecommendation(
                    String.valueOf(parsed.get("recommendedModel")),
                    String.valueOf(parsed.get("reasoning"))
            );
        } catch (Exception ex) {
            log.warn("Could not parse model recommendation JSON: {}", ex.getMessage());
            return null;
        }
    }
}
