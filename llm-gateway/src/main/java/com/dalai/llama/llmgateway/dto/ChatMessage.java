package com.dalai.llama.llmgateway.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * {@code toolCallId}/{@code name} are only set on a {@code role="tool"} message -- the caller's
 * reply to a {@link ToolCall} the model previously requested, correlated back by id.
 * <p>
 * {@code imageDataUris} is only meaningful on a {@code role="user"} message to a multimodal
 * model (e.g. vision analysis of a reference image) -- each entry is a full {@code
 * data:image/...;base64,...} URI, the same one-string convention used for every other binary
 * result in this system (TTS audio, generated images).
 */
public record ChatMessage(
        @NotBlank String role,
        @NotBlank String content,
        String toolCallId,
        String name,
        List<String> imageDataUris
) {
    public ChatMessage(String role, String content) {
        this(role, content, null, null, null);
    }
}
