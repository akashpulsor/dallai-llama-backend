package com.dalai.llama.videogen.dto.shotcontext;

import java.math.BigDecimal;

/** Wire-contract mirror of pre-production-service's own {@code DialogueBeat} -- one dialogue
 * timestamp within this shot's timeline. Presence of any beat with a non-null {@code
 * voiceReferenceUrl} is what drives {@code ShotGenerationOrchestrator} to turn off Seedance's
 * native audio and build a beat-matched cloned-voice track instead. */
public record DialogueBeat(
        BigDecimal startSeconds,
        BigDecimal durationSeconds,
        String text,
        String characterKey,
        String voiceReferenceUrl
) {
}
