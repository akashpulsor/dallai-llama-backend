package com.dalai.llama.llmgateway.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * {@code toolCallId}/{@code name} are only set on a {@code role="tool"} message -- the caller's
 * reply to a {@link ToolCall} the model previously requested, correlated back by id.
 * <p>
 * {@code imageDataUris} is only meaningful on a {@code role="user"} message to a multimodal
 * model (e.g. vision analysis of a reference image) -- each entry is a full {@code
 * data:image/...;base64,...} URI, the same one-string convention used for every other binary
 * result in this system (TTS audio, generated images).
 * <p>
 * {@code content} is deliberately {@code @NotNull} rather than {@code @NotBlank}: every
 * taskKey-driven caller across this system (pre-production-service, post-production-service,
 * trend-intelligence-service, ...) sends an explicit empty string here on purpose -- the real
 * prompt text comes from the rendered {@code prompt_template} for {@link ChatRequest#taskKey()},
 * not from this field. {@code @NotBlank} rejected every one of those calls with a 400 before this
 * fix (content: must not be blank), even though a taskKey + templateVariables request is a
 * completely valid, well-established shape in this codebase.
 */
public record ChatMessage(
        @NotBlank String role,
        @NotNull String content,
        String toolCallId,
        String name,
        List<String> imageDataUris
) {
    public ChatMessage(String role, String content) {
        this(role, content, null, null, null);
    }
}
