package com.dalai.llama.llmgateway.dto;

import java.util.List;

/** {@code providerVoiceId} is what a caller must actually send back when picking a voice (e.g.
 * pre-production-service's {@code PUT /cast-profiles/{id}/builtin-voice}) -- it's stored as-is on
 * {@code CastProfile.builtinVoiceId} and later used directly as the ElevenLabs TTS {@code
 * voice_id}, the same "hand over a value obtained from a prior real call, no re-validation against
 * the source catalog" pattern already used for {@code voiceRefBucket}/{@code voiceRefObjectKey}
 * from a MinIO upload. {@code voiceId} (ours) is only for display/selection identity in the UI. */
public record BuiltinVoiceView(
        String voiceId,
        String providerId,
        String providerVoiceId,
        String displayName,
        String gender,
        String previewAudioUrl,
        /** MinIO object key of the creator-uploaded face image (Pro plan). Null when nothing has
         * been uploaded yet; frontend degrades to no thumbnail. llm-gateway has no MinIO client
         * of its own, so it returns the raw key -- the frontend resolves it against pre-production-
         * service's public MinIO reverse-proxy for display (same pattern already used for cast
         * media). */
        String faceRefObjectKey,
        List<String> languageCodes
) {
}
