package com.dalai.llama.postprod.service;

import java.util.UUID;

/** "User uploads a clip or a generated shot, selects a portion (or the whole thing), asks for a
 * change, optionally with a reference image" -- Kling-style video-to-video editing through fal.ai
 * (same meta-provider pattern as every other capability). Standalone/testable like
 * FoleyGenerationService/MusicGenerationService -- not wired into a durable job/pipeline yet, on
 * purpose, so a specific model can be tried and compared first (see their class comments for the
 * same reasoning). */
public interface VideoEditService {

    /** @param startSeconds/endSeconds null/null edits the whole clip; both set edits only that
     *                                  portion -- exactly the two modes described ("select a
     *                                  portion... or give complete video"). */
    VideoEditResult editVideo(
            UUID tenantId, String idempotencyKey, String sourceVideoUrl,
            Integer startSeconds, Integer endSeconds,
            String editInstruction, String referenceImageUrl, String modelOverride
    );
}
