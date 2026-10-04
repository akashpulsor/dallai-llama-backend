package com.dalai.llama.videogen.dto.generationplan;

/**
 * One line of a shot's prompt-inputs checklist: a piece of the plan, whether it reaches the video
 * prompt, and what it says -- so the creator can see what the model is given before generating.
 */
public record PromptInputView(
        String key,
        String label,
        boolean present,
        String detail
) {
}
