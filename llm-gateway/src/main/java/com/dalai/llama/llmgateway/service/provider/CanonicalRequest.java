package com.dalai.llama.llmgateway.service.provider;

import com.dalai.llama.llmgateway.dto.ChatMessage;
import com.dalai.llama.llmgateway.dto.ToolDefinition;

import java.util.List;
import java.util.Map;

/**
 * Provider-agnostic request shape every {@link LlmProvider} adapter translates into its own
 * wire format. Keeping this separate from {@link com.dalai.llama.llmgateway.dto.ChatRequest}
 * means the public API contract and the internal provider-dispatch contract can evolve
 * independently.
 */
public record CanonicalRequest(
        String modelId,
        /** model_master.type (e.g. "video", "voice_clone", "lip_sync", "tts") -- lets a
         * meta-provider adapter like FalAiProvider pick the right endpoint/payload shape per
         * model without guessing from which params happen to be present. */
        String modelType,
        List<ChatMessage> messages,
        Map<String, Object> params,
        int timeoutMs,
        List<ToolDefinition> tools,
        ProviderLanguageDirective languageDirective,
        ProviderRequestContext requestContext
) {
    /** Keeps provider-adapter tests and legacy internal callers compatible. */
    public CanonicalRequest(String modelId, String modelType, List<ChatMessage> messages,
                            Map<String, Object> params, int timeoutMs, List<ToolDefinition> tools) {
        this(modelId, modelType, messages, params, timeoutMs, tools, null, null);
    }

    /** Keeps callers which only need provider-language routing source-compatible. */
    public CanonicalRequest(String modelId, String modelType, List<ChatMessage> messages,
                            Map<String, Object> params, int timeoutMs, List<ToolDefinition> tools,
                            ProviderLanguageDirective languageDirective) {
        this(modelId, modelType, messages, params, timeoutMs, tools, languageDirective, null);
    }
}
