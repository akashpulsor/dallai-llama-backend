package com.dalai.llama.preprod.service.generation;

/** How the prompt builder reads a {@link PromptInput}: as stored ({@link #RAW}), or as resolved by
 * step-shot continuity -- replaced by a value compatible with the reference image, or dropped
 * (null). */
@FunctionalInterface
public interface PromptInputs {

    PromptInputs RAW = (input, raw) -> raw;

    String resolve(PromptInput input, String raw);
}
