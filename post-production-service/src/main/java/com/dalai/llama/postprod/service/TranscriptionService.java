package com.dalai.llama.postprod.service;

import java.util.UUID;

/** Whole-video-as-one-unit for v1 -- no per-minute segmentation (see DubbingOrchestrator's class
 * comment for why that's a named, not silent, gap). */
public interface TranscriptionService {

    TranscriptionResult transcribe(UUID tenantId, String idempotencyKey, String sourceVideoUrl, String modelOverride);
}
