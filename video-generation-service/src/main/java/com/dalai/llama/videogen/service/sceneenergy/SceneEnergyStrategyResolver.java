package com.dalai.llama.videogen.service.sceneenergy;

import com.dalai.llama.videogen.service.llmgateway.LlmGatewayClient;
import com.dalai.llama.videogen.service.llmgateway.LlmGatewayModelSummary;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** Maps the project's actual TTS model pick to the right {@link SceneEnergyStrategy} via
 * llm-gateway's own {@code model_master.capabilities.emotion_delivery} data (see V81 migration),
 * not a hardcoded model_id check -- the strategies themselves are keyed by {@link
 * SceneEnergyStrategy#strategyKey()}, this class just does the lookup. Reuses {@link
 * LlmGatewayClient#listModels}, the same permitAll internal call the TTS-model dropdown's backing
 * data already goes through, so this needs no new llm-gateway endpoint. */
@Slf4j
@Component
public class SceneEnergyStrategyResolver {

    private final Map<String, SceneEnergyStrategy> strategiesByKey;
    private final SceneEnergyStrategy fallback;
    private final LlmGatewayClient llmGatewayClient;
    private final ObjectMapper objectMapper;

    public SceneEnergyStrategyResolver(
            List<SceneEnergyStrategy> strategies,
            NumericVoiceSettingsSceneEnergyStrategy fallback,
            LlmGatewayClient llmGatewayClient,
            ObjectMapper objectMapper
    ) {
        this.strategiesByKey = strategies.stream()
                .collect(java.util.stream.Collectors.toMap(SceneEnergyStrategy::strategyKey, s -> s));
        this.fallback = fallback;
        this.llmGatewayClient = llmGatewayClient;
        this.objectMapper = objectMapper;
    }

    public SceneEnergyDirective resolve(String tenantId, String ttsModelId, String emotion, String text) {
        String key = ttsModelId == null ? null : resolveStrategyKey(tenantId, ttsModelId);
        return strategiesByKey.getOrDefault(key, fallback).apply(emotion, text);
    }

    /** Dubbing must never fail over this -- any lookup/parse problem just falls back to the
     * default strategy, same as an unmatched key would. */
    private String resolveStrategyKey(String tenantId, String ttsModelId) {
        try {
            return llmGatewayClient.listModels(tenantId, "tts").stream()
                    .filter(m -> ttsModelId.equals(m.modelId()))
                    .findFirst()
                    .map(m -> emotionDeliveryKey(m.capabilities()))
                    .orElse(null);
        } catch (Exception ex) {
            log.warn("Could not resolve emotion_delivery capability for tts_model={}, falling back to default scene-energy strategy", ttsModelId, ex);
            return null;
        }
    }

    private String emotionDeliveryKey(String capabilitiesJson) {
        if (capabilitiesJson == null || capabilitiesJson.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(capabilitiesJson).get("emotion_delivery");
            return node == null || node.isNull() ? null : node.asText();
        } catch (Exception ex) {
            return null;
        }
    }
}
