package com.dalai.llama.videogen.dto;

import java.util.List;
import java.util.UUID;

/**
 * Whether one shot's spoken audio fits the clip it is planned for, before anything is generated.
 *
 * <p>Deliberately mostly numbers. The caller is shown a decision -- shorten the clip, lengthen it,
 * or rewrite the line -- and a creator cannot make that decision from a verdict alone; they need to
 * see that the line runs 12.4 seconds in a shot planned for 8. So the figures that produced the
 * verdict travel with it, and the UI states them rather than paraphrasing.
 *
 * <p>{@code measured} is the field to read first. False means no take has been synthesized for this
 * shot yet and {@code audioSpanSeconds} is a text-derived estimate, which is a genuinely different
 * kind of claim: speaking rate varies by language, by voice and by line, so an estimate is a
 * starting point for a conversation and a measurement is a fact. Showing the two identically is how
 * a creator ends up trusting a number that was never true.
 *
 * @param verdict                     NO_DIALOGUE / EXACT / FITS / AUDIO_SHORTER / AUDIO_LONGER /
 *                                    NEEDS_REWRITE / UNFITTABLE -- see {@code
 *                                    DialogueFitMath.Verdict}. NEEDS_REWRITE means the line
 *                                    overruns by more than the shot is allowed to grow, so
 *                                    rewriting is the remedy rather than paying for the seconds.
 * @param slackFrames                 clip frames minus the frames the audio needs. Positive is dead
 *                                    air at the end of the shot, negative is a line that overruns.
 *                                    In frames because a frame is the smallest difference that can
 *                                    be acted on at all.
 * @param suggestedDurationSeconds    the clip length that would fit this audio, already clamped to
 *                                    what the model will generate. This is what the "resize the
 *                                    shot" action sends. Null when nothing needs changing.
 * @param suggestedTargetAudioSeconds how long a rewritten line would need to be to fit the clip as
 *                                    planned. This is what the "rewrite the line" action sends.
 *                                    Null when the line already fits.
 * @param overlaps                    beats whose audio collides -- one starts before the one before
 *                                    it has stopped. Independent of the overall length, and fixed
 *                                    by retiming beats rather than by resizing the clip.
 */
public record DialogueFitView(
        UUID shotId,
        String shotRef,
        Integer shotNumber,
        String verdict,
        Integer plannedDurationSeconds,
        int fps,
        /** True when the plan stated no frame rate, so a default was assumed for the frame maths. */
        boolean fpsAssumed,
        double audioSpanSeconds,
        double tailSeconds,
        double requiredSeconds,
        int slackFrames,
        double slackSeconds,
        /** The longest this shot may be generated at -- its planned length plus the extension
         * allowance, capped by what the model produces. Clips are billed by the second, so this is
         * what keeps "make the line fit" from meaning "spend whatever it takes". */
        Integer allowedDurationSeconds,
        Integer suggestedDurationSeconds,
        Double suggestedTargetAudioSeconds,
        boolean measured,
        /** The line as it stands, so the rewrite dialog has something to show without another call. */
        String dialogue,
        List<BeatFitView> beats,
        List<OverlapView> overlaps
) {

    /** One beat's timing. {@code spokenSeconds} is what the audio does; {@code plannedSeconds} is
     * what the shot list guessed. The gap between them is the interesting part. */
    public record BeatFitView(
            UUID beatId,
            int orderIndex,
            String characterKey,
            String text,
            double startSeconds,
            Double plannedSeconds,
            double spokenSeconds,
            boolean measured
    ) {}

    public record OverlapView(int earlierOrderIndex, int laterOrderIndex, int overlapFrames) {}
}
