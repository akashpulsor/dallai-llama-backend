package com.dalai.llama.postprod.service;

import java.util.UUID;

/** Whole-video-as-one-unit for v1 -- no per-minute segmentation (see DubbingOrchestrator's class
 * comment for why that's a named, not silent, gap). */
public interface TranscriptionService {

    /** {@code projectId} attributes this call's cost to the project that caused it in
     * llm-gateway's llm_job log. Null for a standalone call that genuinely has no project. */
    TranscriptionResult transcribe(UUID tenantId, UUID projectId, String idempotencyKey, String sourceVideoUrl, String modelOverride);
}
