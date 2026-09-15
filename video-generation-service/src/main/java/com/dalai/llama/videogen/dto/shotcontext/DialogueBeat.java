package com.dalai.llama.videogen.dto.shotcontext;

import java.math.BigDecimal;

/** Wire-contract mirror of pre-production-service's own {@code DialogueBeat} -- one dialogue
 * timestamp within this shot's timeline. Presence of any beat with a non-null {@code
 * clonedVoiceId} (a previously prepared provider voice), {@code voiceReferenceUrl} (clone the
 * actor's own sample), or {@code builtinVoiceId} (speak directly with a stock voice) is what
 * drives {@code ShotGenerationOrchestrator} to turn
 * off Seedance's native audio and build a beat-matched voice track instead; see {@link
 * com.dalai.llama.videogen.service.BeatDubbingService#canAutoDub}. */
public record DialogueBeat(
        BigDecimal startSeconds,
        BigDecimal durationSeconds,
        String text,
        String characterKey,
        String voiceReferenceUrl,
        String clonedVoiceId,
        String clonedVoiceProviderId,
        String builtinVoiceId,
        /** Shot.emotion -- a shot-level attribute, fed into {@code BeatDubbingService}'s {@code
         * SceneEnergyStrategyResolver} so the dubbed delivery matches the shot's intended mood
         * instead of reading as flat/neutral text. Nullable. */
        String emotion,
        /** BCP-47 code from the project's dialogueLanguage (see pre-production-service's own
         * DialogueBeat.languageCode javadoc). Forwarded by {@code BeatDubbingService} into the
         * TTS call as an explicit hint so eleven_multilingual_v2 doesn't misidentify the target
         * language from romanized text alone. Nullable. */
        String languageCode,
        /** How long this line actually takes to speak, measured from the take synthesized ahead of
         * generation ("Prepare all dialogues"), in seconds.
         *
         * <p>Distinct from {@link #durationSeconds}, which is the shot list's PLANNED length --
         * written before anyone heard the line, and so routinely wrong by a second or more. The
         * planned number is still what the creator edits and what the timeline is drawn from; this
         * one is what the audio does.
         *
         * <p>Null when the line has not been synthesized yet, which is the honest state before a
         * dub: callers fall back to the planned length and say the figure is an estimate rather
         * than presenting a guess as a measurement. */
        java.math.BigDecimal measuredSeconds
) {

    /** Pre-measurement arity, kept so existing callers and tests compile unchanged. */
    public DialogueBeat(BigDecimal startSeconds, BigDecimal durationSeconds, String text, String characterKey,
                        String voiceReferenceUrl, String clonedVoiceId, String clonedVoiceProviderId,
                        String builtinVoiceId, String emotion, String languageCode) {
        this(startSeconds, durationSeconds, text, characterKey, voiceReferenceUrl, clonedVoiceId,
                clonedVoiceProviderId, builtinVoiceId, emotion, languageCode, null);
    }

    /** The length to time this beat against: what was measured if anything was, else the plan's
     * guess. One place, because every caller that needs a length needs the same precedence. */
    public BigDecimal effectiveSeconds() {
        return measuredSeconds != null && measuredSeconds.signum() > 0 ? measuredSeconds : durationSeconds;
    }
}
