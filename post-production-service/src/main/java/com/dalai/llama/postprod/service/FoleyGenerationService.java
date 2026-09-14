package com.dalai.llama.postprod.service;

import java.util.UUID;

/** Standalone, directly-testable -- NOT yet wired into DialogueSyncCoordinator's own pipeline.
 * Where foley generation fits relative to dialogue-sync/mixing is its own open design question
 * (see doc's deferred mix-plan scope); this exists so specific fal.ai models can be tried and
 * compared before that's decided, per explicit request: "we will test as much as possible so
 * that we figure out what works well." */
public interface FoleyGenerationService {

    /** {@code projectId} attributes this call's cost to the project that caused it in
     * llm-gateway's llm_job log. Null for a standalone call that genuinely has no project. */
    AudioGenerationResult generateFoley(UUID tenantId, UUID projectId, String idempotencyKey, String sourceVideoUrl, String cueDescription, String modelOverride);
}
