package com.dalai.llama.preprod.dto.music;

import java.util.List;

/**
 * The score for a complete video: one identity, a contiguous timeline of sections, and the single
 * prompt they compose into.
 *
 * <p>Deliberately provider-neutral. {@code masterPrompt} is prose a music model can read, but
 * nothing here knows which model, what its duration unit is, or how it is authenticated -- a
 * {@code MusicPlan} for a thirty-second film is the same object whether it is rendered by
 * ElevenLabs Music or fal.ai ACE-Step.
 *
 * @param masterPrompt        the whole plan expressed as one continuous-composition instruction.
 *                            Composed deterministically from the fields above by
 *                            {@code MasterMusicPromptComposer}, so it can be recomposed after an
 *                            edit rather than being the only copy of the plan.
 * @param endingStrategy      how the final seconds resolve. Stated explicitly so generation is
 *                            asked to compose an ending rather than have one cut into it.
 * @param totalDurationSeconds the film's real length. The last section must end exactly here.
 */
public record MusicPlan(
        GlobalMusicIdentity globalIdentity,
        List<MusicSection> sections,
        String masterPrompt,
        String endingStrategy,
        Double totalDurationSeconds
) {
}
