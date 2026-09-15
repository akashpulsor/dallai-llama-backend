package com.dalai.llama.videogen.service.dialoguefit;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Whether a shot's spoken audio and its clip length agree, measured in frames.
 *
 * <p>Pure arithmetic, no I/O and no Spring: the same function answers the question for the report
 * the creator reads and for the guard that refuses to dispatch. Two copies of this comparison
 * would eventually disagree, and the one the creator saw would be the wrong one.
 *
 * <h2>Why frames</h2>
 * A generated clip is an integer number of frames -- {@code D * fps} of them -- so the only
 * instants at which picture and sound can agree are multiples of {@code 1/fps}. A mismatch smaller
 * than one frame is therefore not a mismatch that can be fixed: there is no shorter or longer clip
 * to ask for. That is the tolerance used here, and it is why this class counts frames rather than
 * comparing seconds against a hand-picked slack like 0.25s -- a quarter of a second is six frames
 * at 24fps and twelve at 48, so a constant in seconds means something different on every project.
 *
 * <p>Clip durations themselves are whole seconds (what the video providers accept), and a whole
 * second is always on the frame grid for an integer fps, so the clip length never needs snapping.
 * What does need snapping is the other direction: a beat start of 1.37s does not land on a frame at
 * 24fps, so {@link #snapUpToFrame} exists for the offsets fed to the mux and to the pause markers.
 *
 * <h2>The two mismatches are not equally bad</h2>
 * Audio LONGER than the clip is the real failure, and the only one that breaks a deliverable: the
 * line is cut off mid-word, either by the model speaking what fits and stopping, or by the mux
 * pinning the dubbed track to the video's length. Nothing downstream can recover it and it has
 * already been paid for.
 *
 * <p>Audio SHORTER than the clip is not a fault at all. Every word is heard; the shot simply runs on
 * afterwards, which is ordinary filmmaking. It is reported only because clips are billed by the
 * second, so a creator may want the spare seconds back -- a cost note, never a warning.
 *
 * <p>Neither is repaired here. This class says what is true and what each remedy would cost;
 * choosing between resizing the clip and rewriting the line is a directorial decision, because the
 * silence may be exactly the held beat the action needs.
 */
public final class DialogueFitMath {

    /** The frame rate to reason in when the plan states none.
     *
     * <p>A fallback for absent data, not a tuning knob -- the frame maths needs a grid to snap to,
     * and the alternative is refusing to say anything about a shot whose plan omitted its frame rate.
     * Every report built on it carries {@code fpsAssumed}, so it is never presented as the shot's
     * actual frame rate. The tail, the shot-length bounds and the estimation rate are all properties
     * on the caller's side instead, because those are judgements a deployment can reasonably differ
     * on; which frame the audio lands on is not. */
    public static final int ASSUMED_FPS = 24;

    /** Guards {@code ceil} against binary-float noise: 2.0 * 24 is 48.000000000000004, and
     * ceiling that gives 49 -- a whole extra frame invented out of nothing. */
    private static final double FRAME_EPSILON = 1e-6;

    private DialogueFitMath() {
    }

    public enum Verdict {
        /** Nothing is spoken in this shot. */
        NO_DIALOGUE,
        /** The audio ends exactly on the clip's last frame. */
        EXACT,
        /** Off by less than one second of dead air, which a whole-second clip cannot shed. */
        FITS,
        /** A second or more of the clip runs on after the last word. */
        AUDIO_SHORTER,
        /** The line runs past the end of the clip, and a longer clip would hold it. */
        AUDIO_LONGER,
        /** The line runs past the end of the longest clip this model will generate. */
        UNFITTABLE;

        /**
         * True only for the verdicts that produce a BROKEN shot -- one where the line is cut off.
         *
         * <p>{@link #AUDIO_SHORTER} is deliberately excluded. A short line in a long shot is not a
         * defect: every word is heard, nothing is lost, and silence around dialogue is ordinary
         * filmmaking -- an establishing beat, a reaction, an action the shot needs time for. It is
         * reported because the clip is billed by the second and a creator may want the seconds back,
         * but it is a cost note, not a fault, and nothing should block or warn on it.
         *
         * <p>The fault is the other direction: audio that does not fit gets CUT. With native audio
         * the model speaks what fits and stops; with auto-dub the mux pins the track to the video's
         * length and severs the rest. Both bake it into a paid render.
         */
        public boolean needsAttention() {
            return this == AUDIO_LONGER || this == UNFITTABLE;
        }
    }

    /**
     * One beat's place on the timeline. {@code spokenSeconds} is how long the audio actually runs
     * -- measured from the synthesized take where there is one, estimated otherwise, which
     * {@code measured} distinguishes. The planned duration from the shot list is deliberately not
     * carried: it is a guess written before anyone heard the line, and using it is what makes the
     * pause markers drift.
     */
    public record BeatSpan(int orderIndex, double startSeconds, double spokenSeconds, boolean measured) {

        public double endSeconds() {
            return startSeconds + spokenSeconds;
        }
    }

    /** Two beats whose audio collides: the second starts before the first has finished speaking. */
    public record Overlap(int earlierOrderIndex, int laterOrderIndex, int overlapFrames) {
    }

    /**
     * What is true about this shot's fit, in raw numbers plus the verdict derived from them.
     *
     * <p>The numbers are the point. A caller that disagrees with how {@link #verdict} was reached
     * can recompute from {@code plannedDurationSeconds}, {@code requiredSeconds} and {@code fps};
     * a caller that only needs to know whether to interrupt the creator reads the verdict. Both
     * come out of one function, so they cannot drift apart.
     *
     * @param slackFrames          clip frames minus required frames. Positive is dead air at the
     *                             end, negative is a line that overruns. Zero is exact.
     * @param suggestedDurationSeconds the whole-second clip length that would fit this audio,
     *                             clamped to what the model will generate. Null when there is
     *                             nothing to change.
     * @param suggestedTargetAudioSeconds how long a rewritten line would have to be to fit the clip
     *                             as planned -- the target handed to the retime call. Null when the
     *                             line already fits.
     * @param measured             false when any beat's length was estimated rather than heard. An
     *                             estimate shown as a measurement is worse than no number.
     * @param fpsAssumed           true when the plan stated no frame rate and {@link #ASSUMED_FPS}
     *                             was used.
     */
    public record Report(
            Verdict verdict,
            Integer plannedDurationSeconds,
            int fps,
            boolean fpsAssumed,
            double audioSpanSeconds,
            double tailSeconds,
            double requiredSeconds,
            int slackFrames,
            double slackSeconds,
            Integer suggestedDurationSeconds,
            Double suggestedTargetAudioSeconds,
            boolean measured,
            List<Overlap> overlaps,
            List<BeatSpan> beats
    ) {

        /** True when a beat's audio starts before the one before it has stopped. Separate from
         * {@link #verdict} on purpose: a shot can have the right total length and still be
         * internally out of order, and the remedy is different -- retiming beats, not resizing the
         * clip. */
        public boolean hasOverlaps() {
            return !overlaps.isEmpty();
        }
    }

    /** Frames from the start of the clip to {@code seconds}, rounded to the nearest frame. */
    public static int framesRound(double seconds, int fps) {
        return (int) Math.round(seconds * fps);
    }

    /** The first frame at or after {@code seconds} -- what a duration has to reach to contain it. */
    public static int framesCeil(double seconds, int fps) {
        return (int) Math.ceil(seconds * fps - FRAME_EPSILON);
    }

    /** {@code seconds} moved forward to the next frame boundary. Beat offsets go through this
     * before they reach a mux or a pause marker, so their alignment is a number we chose rather
     * than whatever the encoder happened to round to. */
    public static double snapUpToFrame(double seconds, int fps) {
        if (fps <= 0) {
            return seconds;
        }
        return framesCeil(seconds, fps) / (double) fps;
    }

    /** {@code seconds} moved back to a frame boundary. For targets a line must come in UNDER. */
    public static double snapDownToFrame(double seconds, int fps) {
        if (fps <= 0) {
            return seconds;
        }
        return Math.floor(seconds * fps + FRAME_EPSILON) / (double) fps;
    }

    /**
     * Evaluates one shot.
     *
     * @param plannedDurationSeconds the clip length from the shot plan. Null or non-positive means
     *                               the plan has no length yet, so there is nothing to compare to.
     * @param plannedFps             the shot's frame rate, or null for {@link #ASSUMED_FPS}.
     * @param beats                  every spoken beat in the shot. Empty means no dialogue.
     * @param tailSeconds            breath left after the last word.
     * @param minShotSeconds         shortest clip the provider will generate -- a trim never goes
     *                               below it, since asking for less comes back clamped.
     * @param maxShotSeconds         longest clip the provider will generate, for the same reason.
     */
    public static Report evaluate(
            Integer plannedDurationSeconds,
            Integer plannedFps,
            List<BeatSpan> beats,
            double tailSeconds,
            int minShotSeconds,
            int maxShotSeconds) {

        boolean fpsAssumed = plannedFps == null || plannedFps <= 0;
        int fps = fpsAssumed ? ASSUMED_FPS : plannedFps;
        List<BeatSpan> sorted = beats == null ? List.<BeatSpan>of() : beats.stream()
                .filter(b -> b != null && b.spokenSeconds() > 0)
                .sorted(Comparator.comparingDouble(BeatSpan::startSeconds))
                .toList();

        if (sorted.isEmpty()) {
            return new Report(Verdict.NO_DIALOGUE, plannedDurationSeconds, fps, fpsAssumed,
                    0, tailSeconds, 0, 0, 0, null, null, true, List.of(), List.of());
        }

        boolean measured = sorted.stream().allMatch(BeatSpan::measured);
        double audioSpan = sorted.stream().mapToDouble(BeatSpan::endSeconds).max().orElse(0);
        double required = audioSpan + tailSeconds;
        List<Overlap> overlaps = findOverlaps(sorted, fps);

        // No planned length to compare against: report the span and stop short of a verdict rather
        // than inventing a planned duration to measure it against.
        if (plannedDurationSeconds == null || plannedDurationSeconds <= 0) {
            return new Report(Verdict.NO_DIALOGUE, plannedDurationSeconds, fps, fpsAssumed,
                    audioSpan, tailSeconds, required, 0, 0,
                    (int) Math.ceil(required - FRAME_EPSILON), null, measured, overlaps, sorted);
        }

        int requiredFrames = framesCeil(required, fps);
        int plannedFrames = plannedDurationSeconds * fps;
        int slackFrames = plannedFrames - requiredFrames;
        double slackSeconds = slackFrames / (double) fps;
        // What a rewritten line would have to come in under to fit the clip as planned. Snapped
        // DOWN: a target on the far side of a frame boundary is a target that does not fit.
        double retimeTarget = snapDownToFrame(Math.max(0, plannedDurationSeconds - tailSeconds), fps);

        if (slackFrames == 0) {
            return new Report(Verdict.EXACT, plannedDurationSeconds, fps, fpsAssumed, audioSpan,
                    tailSeconds, required, slackFrames, slackSeconds, null, null, measured, overlaps, sorted);
        }

        if (slackFrames < 0) {
            int needed = (int) Math.ceil(required - FRAME_EPSILON);
            if (needed <= maxShotSeconds) {
                // Either remedy works: a longer clip, or a shorter line.
                return new Report(Verdict.AUDIO_LONGER, plannedDurationSeconds, fps, fpsAssumed,
                        audioSpan, tailSeconds, required, slackFrames, slackSeconds, needed,
                        retimeTarget, measured, overlaps, sorted);
            }
            // Past the longest clip the model will make, so no duration holds this line -- only
            // rewriting it, or splitting it across shots, can. The suggested duration is the cap,
            // which is the most that can be generated, not a length that fits.
            return new Report(Verdict.UNFITTABLE, plannedDurationSeconds, fps, fpsAssumed, audioSpan,
                    tailSeconds, required, slackFrames, slackSeconds, maxShotSeconds,
                    snapDownToFrame(Math.max(0, maxShotSeconds - tailSeconds), fps),
                    measured, overlaps, sorted);
        }

        // Dead air. Trimming is only worth offering when it buys a whole second, because that is
        // the smallest unit of clip length a provider will take.
        int trimmedTo = Math.max(minShotSeconds, (int) Math.ceil(required - FRAME_EPSILON));
        if (trimmedTo < plannedDurationSeconds) {
            return new Report(Verdict.AUDIO_SHORTER, plannedDurationSeconds, fps, fpsAssumed,
                    audioSpan, tailSeconds, required, slackFrames, slackSeconds, trimmedTo,
                    retimeTarget, measured, overlaps, sorted);
        }
        // Under a second of tail, or already at the provider's floor -- there is no shorter clip to
        // ask for, so this counts as fitting however many frames are left over.
        return new Report(Verdict.FITS, plannedDurationSeconds, fps, fpsAssumed, audioSpan,
                tailSeconds, required, slackFrames, slackSeconds, null, null, measured, overlaps, sorted);
    }

    /** Compared in frames, not seconds: two beats a thousandth of a second apart are touching, not
     * overlapping, and reporting that as a collision would bury the real ones. */
    private static List<Overlap> findOverlaps(List<BeatSpan> sorted, int fps) {
        List<Overlap> overlaps = new ArrayList<>();
        for (int i = 0; i + 1 < sorted.size(); i++) {
            BeatSpan earlier = sorted.get(i);
            BeatSpan later = sorted.get(i + 1);
            int overlapFrames = framesCeil(earlier.endSeconds(), fps) - framesRound(later.startSeconds(), fps);
            if (overlapFrames > 0) {
                overlaps.add(new Overlap(earlier.orderIndex(), later.orderIndex(), overlapFrames));
            }
        }
        return overlaps;
    }

    /**
     * The gap to leave before {@code nextStartSeconds}, given that speech has already run to
     * {@code cursorSeconds}.
     *
     * <p>Split out because the pause markers fed to the voice model are exactly this number, and
     * taking it from the planned beat length instead of the spoken one is what makes every beat
     * after an over-running one land late. Never negative: once speech has passed the next beat's
     * start there is no gap to insert, and a negative one would silently pull the timeline
     * backwards. Snapped to the frame grid so the gap is a number we chose.
     */
    public static double gapBefore(double cursorSeconds, double nextStartSeconds, int fps) {
        double gap = snapUpToFrame(nextStartSeconds, fps) - cursorSeconds;
        return gap <= 0 ? 0 : gap;
    }
}
