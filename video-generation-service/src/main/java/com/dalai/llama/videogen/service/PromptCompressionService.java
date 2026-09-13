package com.dalai.llama.videogen.service;

import java.util.UUID;

public interface PromptCompressionService {

    /** No-op (compressionApplied=false) if the prompt is already within maxLength. Doc §9's
     * "preserve every named subject" instruction lives in llm-gateway's PROMPT_COMPRESSION
     * template, not here. */
    CompressionResult compressIfNeeded(UUID projectId, String prompt, int maxLength);
}
