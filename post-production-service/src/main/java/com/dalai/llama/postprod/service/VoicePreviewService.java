package com.dalai.llama.postprod.service;

import java.util.UUID;

/** "Let me hear how the cloned voice sounds" -- synthesizes arbitrary (or default sample) text
 * in an existing voice profile's cloned voice and hands back a durable, playable URL. */
public interface VoicePreviewService {

    VoicePreviewResult preview(UUID tenantId, UUID voiceProfileId, String text, String modelOverride);

    record VoicePreviewResult(UUID voiceProfileId, String characterRef, String language, String audioUrl) {
    }
}
