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
        String builtinVoiceId,
        /** Shot.emotion -- a shot-level attribute (same value for every beat in that shot), fed
         * into beat-dubbing's scene-energy TTS lever so a shot's dubbed delivery matches its
         * intended mood instead of reading as flat/neutral text. Nullable, same tolerant "no
         * signal, use a sensible default" handling as the voice fields above. */
        String emotion,
        /** BCP-47 code from ProjectConfig.dialogueLanguage (e.g. hi-IN, hi-Latn-IN, en-US). Fed to
         * the TTS provider as an explicit language hint so eleven_multilingual_v2 doesn't over-rely
         * on its English prior when the text happens to be romanized Hindi/Hinglish -- the exact
         * bug where a Hinglish script was speaking with an English accent. Nullable: absent means
         * let the provider infer from the text as before. */
        String languageCode
) {
}
