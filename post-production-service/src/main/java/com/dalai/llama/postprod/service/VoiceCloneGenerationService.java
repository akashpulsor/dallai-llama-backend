package com.dalai.llama.postprod.service;

import java.util.UUID;

public interface VoiceCloneGenerationService {

    /** @param modelOverride null/blank uses the service's own configured default -- lets a
     *                       caller try a different fal.ai-hosted voice-clone model per request. */
    VoiceCloneResult cloneVoice(UUID tenantId, String idempotencyKey, String referenceAudioUrl, String targetLanguage, String modelOverride);
}
