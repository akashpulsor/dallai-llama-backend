package com.dalai.llama.postprod.service.preproduction;

import java.util.UUID;

/** One shot's dialogue + cast details as pre-production-service will eventually serve them.
 * Keyed by project_id primarily; script_id/screenplay_id carried through when pre-production
 * needs it as a secondary key -- the exact keying is still pre-production-service's own call to
 * finalize, this shape is deliberately tolerant of either.
 *
 * <p>Field names deliberately match creator-service's own established screenplay vocabulary
 * (ScreenplaySceneView/ScreenplayRunView: dialogueScript, sourceDialogueLanguage, languageCode,
 * character, shotNumber) rather than inventing new spellings for the same concepts -- the real
 * pre-production-service, whenever it's built, is almost certainly going to speak this same
 * vocabulary since it's migrating out of the same domain, so matching it now means the eventual
 * real HttpPreProductionClient response mapping is closer to a no-op than a translation layer. */
public record PreProductionShotDetails(
        UUID projectId,
        UUID scriptId,
        String shotRef,
        Integer shotNumber,
        String character,
        String dialogueScript,
        String sourceDialogueLanguage,
        String languageCode,
        /** Source audio to clone the character's voice from -- matches ScreenplayRunView's own
         * "voiceTrack" concept. Null when no reference audio exists yet for this character
         * (voice-clone dispatch fails fast with a clear error rather than silently guessing). */
        String referenceAudioUrl
) {
}
