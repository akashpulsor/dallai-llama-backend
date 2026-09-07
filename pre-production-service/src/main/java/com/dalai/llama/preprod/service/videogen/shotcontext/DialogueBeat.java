package com.dalai.llama.preprod.service.videogen.shotcontext;

import java.math.BigDecimal;

/** One dialogue timestamp within this shot's timeline, resolved from {@code ShotDialogueBeat} plus
 * the speaking character's cast voice -- video-generation-service uses this to turn off Seedance's
 * native audio and synthesize/mux a beat-matched voice track instead. Exactly one of {@code
 * voiceReferenceUrl} (clone the actor's own sample) or {@code builtinVoiceId} (speak directly with
 * a stock ElevenLabs voice, no cloning) is set when the character has a usable cast voice; both
 * null means no assigned voice at all. A shot with any beat missing both falls back to normal
 * (native-audio) generation for the whole shot. */
public record DialogueBeat(
        BigDecimal startSeconds,
        BigDecimal durationSeconds,
        String text,
        String characterKey,
        String voiceReferenceUrl,
        String builtinVoiceId
) {
}
