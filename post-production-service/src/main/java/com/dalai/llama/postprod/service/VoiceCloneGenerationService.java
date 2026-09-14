package com.dalai.llama.postprod.service;

import java.util.UUID;

public interface VoiceCloneGenerationService {

    /** @param modelOverride null/blank uses the service's own configured default -- lets a
     *                       caller try a different fal.ai-hosted voice-clone model per request. */
    /** {@code projectId} attributes this call's cost to the project that caused it in
     * llm-gateway's llm_job log. Null for a standalone call that genuinely has no project. */
    VoiceCloneResult cloneVoice(UUID tenantId, UUID projectId, String idempotencyKey, String referenceAudioUrl, String targetLanguage, String modelOverride);
}
