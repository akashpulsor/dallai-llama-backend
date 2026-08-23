package com.dalai.llama.postprod.service;

import java.util.UUID;

/** The per-shot step that was missing: "speak this shot's dialogue line as this cloned voice."
 * fal-ai/minimax/voice-clone (the real, verified voice-clone model this now dispatches to) fuses
 * cloning and synthesis into one call -- there is no separate "reuse this voice_id" endpoint, so
 * synthesizing a new line means calling that same endpoint again with the reference audio AND the
 * new text, not just the previously-returned providerVoiceId. {@code referenceAudioUrl} is
 * required for that reason; {@code providerVoiceId} is kept only as a cache-hit marker (see
 * DialogueSyncCoordinator's VoiceProfile reuse check), not something this call can act on alone. */
public interface VoiceSynthesisService {

    VoiceSynthesisResult synthesize(UUID tenantId, String idempotencyKey, String providerVoiceId, String referenceAudioUrl, String text, String language, String modelOverride);
}
