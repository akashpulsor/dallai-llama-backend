package com.dalai.llama.postprod.service;

import java.util.UUID;

/** The per-shot step that was missing: "speak this shot's dialogue line as this cloned voice."
 * A cloned voice (VoiceProfile.providerVoiceId) is an identifier, not a playable asset -- this
 * is what turns it into the actual dubbed audio for one shot's line, which is what LipSyncGenerationService
 * actually needs. */
public interface VoiceSynthesisService {

    VoiceSynthesisResult synthesize(UUID tenantId, String idempotencyKey, String providerVoiceId, String text, String language, String modelOverride);
}
