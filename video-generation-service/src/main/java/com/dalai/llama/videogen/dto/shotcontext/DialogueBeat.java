package com.dalai.llama.videogen.dto.shotcontext;

import java.math.BigDecimal;

/** Wire-contract mirror of pre-production-service's own {@code DialogueBeat} -- one dialogue
 * timestamp within this shot's timeline. Presence of any beat with a non-null {@code
 * voiceReferenceUrl} (clone the actor's own sample) or {@code builtinVoiceId} (speak directly with
 * a stock ElevenLabs voice, no cloning) is what drives {@code ShotGenerationOrchestrator} to turn
 * off Seedance's native audio and build a beat-matched voice track instead; see {@link
 * com.dalai.llama.videogen.service.BeatDubbingService#canAutoDub}. */
public record DialogueBeat(
        BigDecimal startSeconds,
        BigDecimal durationSeconds,
        String text,
        String characterKey,
        String voiceReferenceUrl,
        String builtinVoiceId,
        /** Shot.emotion -- a shot-level attribute, fed into {@code BeatDubbingService}'s {@code
         * SceneEnergyStrategyResolver} so the dubbed delivery matches the shot's intended mood
         * instead of reading as flat/neutral text. Nullable. */
        String emotion,
        /** BCP-47 code from the project's dialogueLanguage (see pre-production-service's own
         * DialogueBeat.languageCode javadoc). Forwarded by {@code BeatDubbingService} into the
         * TTS call as an explicit hint so eleven_multilingual_v2 doesn't misidentify the target
         * language from romanized text alone. Nullable. */
        String languageCode
) {
}
