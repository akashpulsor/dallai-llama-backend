package com.dalai.llama.preprod.service.videogen.shotcontext;

import java.math.BigDecimal;

/** One dialogue timestamp within this shot's timeline, resolved from {@code ShotDialogueBeat} plus
 * the speaking character's cast voice reference -- video-generation-service uses this to turn off
 * Seedance's native audio and synthesize/mux a beat-matched cloned-voice track instead. {@code
 * voiceReferenceUrl} is null when the character has no assigned cast voice sample; a shot with any
 * beat missing a voice reference falls back to normal (native-audio) generation for the whole shot. */
public record DialogueBeat(
        BigDecimal startSeconds,
        BigDecimal durationSeconds,
        String text,
        String characterKey,
        String voiceReferenceUrl
) {
}
