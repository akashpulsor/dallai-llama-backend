package com.dalai.llama.llmgateway.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.Map;

/**
 * A tool the CALLER can execute -- the gateway only ever relays this to the provider and relays
 * back whatever {@link ToolCall} the model asks for. It never invokes anything itself.
 * {@code parameters} is a JSON-Schema object describing the tool's arguments, passed through
 * to the provider as-is (the caller is responsible for supplying a schema the target model
 * understands).
 */
public record ToolDefinition(
        @NotBlank String name,
        String description,
        Map<String, Object> parameters
) {
}
