package com.dalai.llama.postprod.service;

import java.util.UUID;

/** Standalone, directly-testable -- same shape as {@link FoleyGenerationService}: a manual CTA
 * once a video exists, not wired into any automatic pipeline. Lets a creator pick from every
 * registered type=upscale model (GET /v1/post-production/models?type=upscale) and try it against
 * a specific clip. */
public interface UpscaleGenerationService {

    /** {@code projectId} attributes this call's cost to the project that caused it in
     * llm-gateway's llm_job log. Null for a standalone call that genuinely has no project. */
    UpscaleGenerationResult upscale(UUID tenantId, UUID projectId, String idempotencyKey, String sourceVideoUrl, String modelOverride, Double durationSeconds);
}
