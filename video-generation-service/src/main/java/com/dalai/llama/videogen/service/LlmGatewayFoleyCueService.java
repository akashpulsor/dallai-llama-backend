package com.dalai.llama.videogen.service;

import com.dalai.llama.videogen.dto.shotcontext.Character;
import com.dalai.llama.videogen.dto.shotcontext.ShotContext;
import com.dalai.llama.videogen.service.llmgateway.LlmGatewayChatRequest;
import com.dalai.llama.videogen.service.llmgateway.LlmGatewayChatRequest.LlmGatewayMessage;
import com.dalai.llama.videogen.service.llmgateway.LlmGatewayChatResponse;
import com.dalai.llama.videogen.service.llmgateway.LlmGatewayClient;
import com.dalai.llama.videogen.web.TenantContextHolder;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
public class LlmGatewayFoleyCueService implements FoleyCueService {

    private final LlmGatewayClient llmGatewayClient;
    private final ObjectMapper objectMapper;
    private final String defaultModel;

    public LlmGatewayFoleyCueService(
            LlmGatewayClient llmGatewayClient,
            ObjectMapper objectMapper,
            @Value("${video-gen.llm-gateway.default-compression-model}") String defaultModel
    ) {
        this.llmGatewayClient = llmGatewayClient;
        this.objectMapper = objectMapper;
        this.defaultModel = defaultModel;
    }

    @Override
    public List<DerivedFoleyCue> deriveCues(ShotContext shotContext) {
        int durationMs = shotContext.technical() != null && shotContext.technical().durationSeconds() != null
                ? shotContext.technical().durationSeconds() * 1000
                : 5000;
        String shotDescription = describeShotForFoley(shotContext, durationMs);

        UUID tenantId = TenantContextHolder.get().tenantId();
        LlmGatewayChatResponse response;
        try {
            response = llmGatewayClient.chat(
                    tenantId.toString(),
                    "foley-cue-" + UUID.randomUUID(),
                    new LlmGatewayChatRequest(
                            defaultModel,
                            List.of(new LlmGatewayMessage("user", shotDescription)),
                            Map.of(),
                            "FOLEY_CUE_DERIVATION",
                            Map.of()
                    )
            );
        } catch (RuntimeException ex) {
            // Cue sheet is metadata, not a blocking dependency of dispatch -- a failed derivation
            // must not stop the video from generating.
            log.warn("Foley cue derivation failed shotRef={} errorMessage={}", shotContext.shotRef(), ex.getMessage());
            return List.of();
        }
        return parseCues(response == null ? null : response.response());
    }

    private List<DerivedFoleyCue> parseCues(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return List.of(objectMapper.readValue(json, DerivedFoleyCue[].class));
        } catch (Exception ex) {
            log.warn("Could not parse foley cue JSON, discarding cue sheet for this generation: {}", ex.getMessage());
            return List.of();
        }
    }

    private String describeShotForFoley(ShotContext shotContext, int durationMs) {
        StringBuilder description = new StringBuilder();
        description.append("Duration: ").append(durationMs).append("ms\n");
        if (shotContext.narrative() != null && shotContext.narrative().scriptLine() != null) {
            description.append("Script line: ").append(shotContext.narrative().scriptLine()).append("\n");
        }
        if (shotContext.characters() != null) {
            for (Character character : shotContext.characters()) {
                if (character.performanceDirection() != null) {
                    description.append("Character action: ").append(character.performanceDirection()).append("\n");
                }
            }
        }
        if (shotContext.environment() != null) {
            if (shotContext.environment().location() != null) {
                description.append("Location: ").append(shotContext.environment().location()).append("\n");
            }
            if (shotContext.environment().environmentalEffects() != null) {
                description.append("Environmental effects: ").append(shotContext.environment().environmentalEffects()).append("\n");
            }
        }
        if (shotContext.productBrand() != null && Boolean.TRUE.equals(shotContext.productBrand().isProductHeroShot())) {
            description.append("Product hero shot: ").append(shotContext.productBrand().productPlacement()).append("\n");
        }
        return description.toString();
    }
}
