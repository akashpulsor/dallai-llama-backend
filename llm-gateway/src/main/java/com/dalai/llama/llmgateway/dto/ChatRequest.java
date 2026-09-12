package com.dalai.llama.llmgateway.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ChatRequest(
        String schemaVersion,
        @NotBlank String modelId,
        @NotEmpty @Valid List<ChatMessage> messages,
        Map<String, Object> params,
        List<Object> cacheMarkers,
        /** Passed straight through to the provider (see {@link ToolDefinition}) -- the gateway
         * never executes a tool itself. Optional; absent/empty means no function calling. */
        @Valid List<ToolDefinition> tools,
        /** When set, the active {@code prompt_template} for this task is rendered (see
         * {@link com.dalai.llama.llmgateway.service.PromptTemplateService}) and prepended as the
         * system message, ahead of {@code messages} -- callers hold no instruction-text string
         * literals of their own. Optional; a plain {@code messages}-only request is unaffected. */
        String taskKey,
        Map<String, String> templateVariables,
        /** Optional project attribution -- when set, persisted on the job row so per-project cost
         * can be summed. Absent for calls with no project context (estimates, internal calls). */
        UUID projectId,
        /** Platform language selection. Provider-specific codes and parameter names never enter the public API. */
        @Valid LanguageSelection language
) {
    /** Keeps internal callers compilable while they adopt the typed language contract. */
    public ChatRequest(String schemaVersion, String modelId, List<ChatMessage> messages,
                       Map<String, Object> params, List<Object> cacheMarkers, List<ToolDefinition> tools,
                       String taskKey, Map<String, String> templateVariables, UUID projectId) {
        this(schemaVersion, modelId, messages, params, cacheMarkers, tools, taskKey, templateVariables, projectId, null);
    }
}
