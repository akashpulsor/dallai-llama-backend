package com.dalai.llama.postprod.service;

import java.util.UUID;

public interface LipSyncGenerationService {

    /** @param dialogueAudioUrl the (possibly just-cloned, possibly original) dialogue audio to
     *                          sync the source video's mouth movement to.
     * @param durationSeconds the source clip's real duration, for llm-gateway's duration-priced
     *                        billing (fal-ai/sync-lipsync is $/minute) -- null when the caller
     *                        doesn't have it (the automatic DialogueSyncCoordinator pipeline
     *                        doesn't currently track per-shot duration), in which case
     *                        LlmGatewayLipSyncGenerationService falls back to the same
     *                        DEFAULT_SHOT_DURATION_SECONDS approximation
     *                        ShotListGenerationService's own shot-duration estimate already uses. */
    /** {@code projectId} attributes this call's cost to the project that caused it in
     * llm-gateway's llm_job log. Null for a standalone call that genuinely has no project. */
    LipSyncResult syncLips(UUID tenantId, UUID projectId, String idempotencyKey, String sourceVideoUrl, String dialogueAudioUrl, String modelOverride, Double durationSeconds);
}
