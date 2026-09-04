package com.dalai.llama.videogen.service;

import com.dalai.llama.videogen.dto.FeatureFlags;
import com.dalai.llama.videogen.dto.shotcontext.ShotContext;
import com.dalai.llama.videogen.service.llmgateway.LlmGatewayClient;
import com.dalai.llama.videogen.service.llmgateway.LlmGatewayPromptFormatResponse;
import com.dalai.llama.videogen.web.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Thin HTTP delegate to llm-gateway's {@code /v1/prompt/format}. Per-model prompt-shape
 * knowledge (Seedance / Wan / default composition; negative-prompt library; dialogue phoneme
 * respelling) now lives with the model catalog on the llm-gateway side -- this service just
 * ships the raw shot data over the wire and persists the composed result in {@code ShotPrompt}.
 *
 * <p>The {@link PromptBuilderService} interface stays intact so no upstream caller
 * (ShotGenerationOrchestrator, PromptCompressionService) had to be rewired for the move.
 * Video-gen's ShotContext serializes structurally to llm-gateway's PromptDtos.ShotContext -- same
 * field names, Jackson stringifies typed enums into llm-gateway's String enum fields.
 */
@Service
@RequiredArgsConstructor
public class DefaultPromptBuilderService implements PromptBuilderService {

    private final LlmGatewayClient llmGatewayClient;

    @Override
    public BuiltPrompt buildPrompt(ShotContext shotContext, FeatureFlags effectiveFlags, String modelId) {
        String tenantId = TenantContextHolder.get().tenantId().toString();
        LlmGatewayPromptFormatResponse response = llmGatewayClient.formatPrompt(
                tenantId, new FormatRequest(modelId, shotContext, effectiveFlags));
        return new BuiltPrompt(response.positive(), response.negative());
    }

    @Override
    public int maxPromptLengthFor(String modelId) {
        String tenantId = TenantContextHolder.get().tenantId().toString();
        return llmGatewayClient.getMaxPromptLength(tenantId, modelId);
    }

    /** Wire body for POST /v1/prompt/format -- structural mirror of llm-gateway's
     * PromptDtos.PromptFormatRequest. Kept private since only this service posts it. */
    private record FormatRequest(String modelId, ShotContext shotContext, FeatureFlags flags) {}
}
