package com.dalai.llama.postprod.service;

import java.util.UUID;

public interface LipSyncGenerationService {

    /** @param dialogueAudioUrl the (possibly just-cloned, possibly original) dialogue audio to
     *                          sync the source video's mouth movement to. */
    LipSyncResult syncLips(UUID tenantId, String idempotencyKey, String sourceVideoUrl, String dialogueAudioUrl, String modelOverride);
}
