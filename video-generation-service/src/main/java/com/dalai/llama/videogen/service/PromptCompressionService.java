package com.dalai.llama.videogen.service;

import java.util.UUID;

public interface PromptCompressionService {

    /** No-op (compressionApplied=false) if the prompt is already within maxLength. Doc §9's
     * "preserve every named subject" instruction lives in llm-gateway's PROMPT_COMPRESSION
     * template, not here.
     *
     * <p>{@code targetModelId} is the video model the compressed prompt will be sent to. The
     * compression step is where the full shot plan gets fitted to a model's budget -- rewritten
     * into the shape that model reads best, rather than having fields pruned before they are ever
     * composed. Assembly's job is to lose nothing; this is where it gets made to fit. */
    CompressionResult compressIfNeeded(UUID projectId, String prompt, int maxLength, String targetModelId);

    /** Model-agnostic form -- compression still runs, just without the per-model shaping hint. */
    default CompressionResult compressIfNeeded(UUID projectId, String prompt, int maxLength) {
        return compressIfNeeded(projectId, prompt, maxLength, null);
    }
}
