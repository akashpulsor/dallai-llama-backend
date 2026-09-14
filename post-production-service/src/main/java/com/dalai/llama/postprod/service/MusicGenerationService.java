package com.dalai.llama.postprod.service;

import java.util.UUID;

/** Same standalone-and-testable status as FoleyGenerationService -- see its class comment. */
public interface MusicGenerationService {

    /** {@code projectId} attributes this call's cost to the project that caused it in
     * llm-gateway's llm_job log. Null for a standalone call that genuinely has no project. */
    AudioGenerationResult generateMusic(UUID tenantId, UUID projectId, String idempotencyKey, String moodPrompt, Integer durationSeconds, String modelOverride);
}
