package com.dalai.llama.postprod.dto;

import java.util.UUID;

public record VoicePreviewView(
        UUID voiceProfileId,
        String characterRef,
        /** The language this cloned voice is for -- from the voice_profile row itself, which is
         * always exactly the language it was cloned/synthesized for (VoiceProfile is keyed by
         * (tenant, project, character, language), so this is never ambiguous). */
        String language,
        String audioUrl
) {
}
