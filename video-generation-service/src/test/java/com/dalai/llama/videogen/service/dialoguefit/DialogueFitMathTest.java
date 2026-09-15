package com.dalai.llama.videogen.service.dialoguefit;

import com.dalai.llama.videogen.service.dialoguefit.DialogueFitMath.BeatSpan;
import com.dalai.llama.videogen.service.dialoguefit.DialogueFitMath.Report;
import com.dalai.llama.videogen.service.dialoguefit.DialogueFitMath.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The cases a shot's dialogue and its clip length can be in, and what each one should be told. */
class DialogueFitMathTest {

    private static final double TAIL = 0.4;
    private static final int MIN_SHOT = 3;
    private static final int MAX_SHOT = 10;
    /** Generous here on purpose: these tests are about the frame maths, so the cost allowance is set
     * wide enough not to interfere. The allowance has its own tests. */
    private static final double MAX_EXTENSION = 10;

    private static Report evaluate(Integer duration, Integer fps, BeatSpan... beats) {
        return DialogueFitMath.evaluate(duration, fps, List.of(beats), TAIL, MIN_SHOT, MAX_SHOT, MAX_EXTENSION);
    }

    private static BeatSpan measured(int order, double start, double spoken) {
        return new BeatSpan(order, start, spoken, true);
    }

    @Test
    @DisplayName("a shot with nothing spoken in it has nothing to fit")
    void noDialogue() {
        Report report = DialogueFitMath.evaluate(8, 24, List.of(), TAIL, MIN_SHOT, MAX_SHOT, MAX_EXTENSION);

        assertThat(report.verdict()).isEqualTo(Verdict.NO_DIALOGUE);
        assertThat(report.suggestedDurationSeconds()).isNull();
        assertThat(report.suggestedTargetAudioSeconds()).isNull();
    }

    @Test
    @DisplayName("the reported bug: two short beats in a ten-second shot leave seconds of silence")
    void audioShorterThanShot() {
        Report report = evaluate(10, 24, measured(0, 0, 1.2), measured(1, 1.5, 0.9));

        // Speech ends at 2.4s, plus the tail needs 2.8s. The remaining 7.2s is a character standing
        // there having stopped talking -- which nothing downstream can see, because it "fits".
        assertThat(report.verdict()).isEqualTo(Verdict.AUDIO_SHORTER);
        assertThat(report.audioSpanSeconds()).isEqualTo(2.4);
        assertThat(report.requiredSeconds()).isEqualTo(2.8);
        // 240 frames of clip, 68 needed (2.8s is 67.2 frames, and a clip contains whole ones):
        // 172 frames of silence. Reported in frames because that is the unit anything can be done in.
        assertThat(report.slackFrames()).isEqualTo(172);
        assertThat(report.slackSeconds()).isCloseTo(172 / 24.0, org.assertj.core.data.Offset.offset(1e-9));
        // Both ways out are offered, and neither is taken.
        assertThat(report.suggestedDurationSeconds()).isEqualTo(3);
        // Not 9.6: that is 230.4 frames at 24fps, and a line has to come in UNDER a frame boundary,
        // so the target is the 230th frame. Snapping up here would set a target that cannot fit.
        assertThat(report.suggestedTargetAudioSeconds())
                .isCloseTo(230 / 24.0, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    @DisplayName("a trim never goes below the shortest clip the provider will make")
    void trimStopsAtTheProviderFloor() {
        Report report = evaluate(9, 24, measured(0, 0, 0.5));

        // 0.9s of audio would "want" a 1s clip, but asking for less than the floor comes back
        // clamped, so it would not be the length we asked for.
        assertThat(report.verdict()).isEqualTo(Verdict.AUDIO_SHORTER);
        assertThat(report.suggestedDurationSeconds()).isEqualTo(MIN_SHOT);
    }

    @Test
    @DisplayName("dead air the provider's whole-second clips cannot shed is not a problem to raise")
    void subSecondDeadAirFits() {
        // Needs 4.4s in a 5s shot: 0.6s over, but there is no 4.4s clip to ask for.
        Report report = evaluate(5, 24, measured(0, 0, 4.0));

        assertThat(report.verdict()).isEqualTo(Verdict.FITS);
        assertThat(report.verdict().needsAttention()).isFalse();
        assertThat(report.suggestedDurationSeconds()).isNull();
    }

    @Test
    @DisplayName("a line that overruns its shot asks for a longer clip, or a shorter line")
    void audioLongerThanShot() {
        Report report = evaluate(5, 24, measured(0, 0, 6.1));

        assertThat(report.verdict()).isEqualTo(Verdict.AUDIO_LONGER);
        assertThat(report.slackFrames()).isNegative();
        assertThat(report.suggestedDurationSeconds()).isEqualTo(7);
        // 4.6s is 110.4 frames; the last frame a line can end on and still fit is the 110th.
        assertThat(report.suggestedTargetAudioSeconds())
                .isCloseTo(110 / 24.0, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    @DisplayName("past the longest clip the model makes, no duration holds the line at all")
    void unfittable() {
        Report report = evaluate(8, 24, measured(0, 0, 18.0));

        assertThat(report.verdict()).isEqualTo(Verdict.UNFITTABLE);
        // The cap is the most that can be generated, not a length that fits -- so the only real
        // remedy is a shorter line, and that is the target given.
        assertThat(report.suggestedDurationSeconds()).isEqualTo(MAX_SHOT);
        assertThat(report.suggestedTargetAudioSeconds())
                .isCloseTo(230 / 24.0, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    @DisplayName("audio landing exactly on the last frame is exact")
    void exact() {
        Report report = evaluate(5, 25, measured(0, 0, 4.6));

        assertThat(report.verdict()).isEqualTo(Verdict.EXACT);
        assertThat(report.slackFrames()).isZero();
    }

    @Test
    @DisplayName("tolerance is one frame, so it means the same thing at every frame rate")
    void toleranceScalesWithFrameRate() {
        // One frame short of exact: at 24fps that is 1/24s, at 48fps 1/48s. Both are one frame.
        Report at24 = evaluate(5, 24, measured(0, 0, 4.6 + 1.0 / 24));
        Report at48 = evaluate(5, 48, measured(0, 0, 4.6 + 1.0 / 48));

        assertThat(at24.slackFrames()).isEqualTo(-1);
        assertThat(at48.slackFrames()).isEqualTo(-1);
        assertThat(at24.verdict()).isEqualTo(Verdict.AUDIO_LONGER);
        assertThat(at48.verdict()).isEqualTo(Verdict.AUDIO_LONGER);
    }

    @Test
    @DisplayName("a whole second of clip is a whole number of frames, and not one more")
    void wholeSecondsDoNotInventAFrame() {
        // 2.0 * 24 is 48.000000000000004 in binary floating point. Ceiling it naively gives 49 --
        // a frame that does not exist, turning an exact fit into an overrun.
        assertThat(DialogueFitMath.framesCeil(2.0, 24)).isEqualTo(48);
        assertThat(DialogueFitMath.framesCeil(0.1 + 0.2, 100)).isEqualTo(30);
    }

    @Test
    @DisplayName("an unstated frame rate is assumed, and reported as assumed")
    void assumedFrameRate() {
        Report report = evaluate(5, null, measured(0, 0, 4.0));

        assertThat(report.fps()).isEqualTo(DialogueFitMath.ASSUMED_FPS);
        assertThat(report.fpsAssumed()).isTrue();
    }

    @Test
    @DisplayName("an estimated beat makes the whole report an estimate")
    void oneEstimateTaintsTheReport() {
        Report report = evaluate(10, 24,
                measured(0, 0, 1.0),
                new BeatSpan(1, 2.0, 3.0, false));

        assertThat(report.measured()).isFalse();
    }

    @Test
    @DisplayName("a beat starting before the one before it has stopped is reported as an overlap")
    void overlapsAreFound() {
        // The first beat was planned for 1s but actually runs 2.5s, so it is still being spoken when
        // the second is supposed to start. This is what made every later beat drift late.
        Report report = evaluate(10, 24, measured(0, 0, 2.5), measured(1, 1.5, 1.0));

        assertThat(report.hasOverlaps()).isTrue();
        assertThat(report.overlaps()).singleElement()
                .satisfies(overlap -> {
                    assertThat(overlap.earlierOrderIndex()).isZero();
                    assertThat(overlap.laterOrderIndex()).isEqualTo(1);
                    assertThat(overlap.overlapFrames()).isEqualTo(24);
                });
    }

    @Test
    @DisplayName("beats out of order are still measured from the last one to finish")
    void spanIsTheLatestEndNotTheLastListed() {
        Report report = evaluate(10, 24, measured(1, 4.0, 1.0), measured(0, 0.0, 2.0));

        assertThat(report.audioSpanSeconds()).isEqualTo(5.0);
    }

    @Test
    @DisplayName("a gap is never negative, so an overrunning beat cannot pull the timeline back")
    void gapNeverGoesNegative() {
        // Speech has already run to 2.5s when the next beat was supposed to start at 1.5s.
        assertThat(DialogueFitMath.gapBefore(2.5, 1.5, 24)).isZero();
        // And a normal gap lands on a frame boundary rather than wherever the decimal fell.
        assertThat(DialogueFitMath.gapBefore(1.0, 1.37, 24))
                .isCloseTo(0.375, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    @DisplayName("a shot with no planned length yet is not judged against an invented one")
    void noPlannedDuration() {
        Report report = evaluate(null, 24, measured(0, 0, 3.0));

        assertThat(report.verdict()).isEqualTo(Verdict.NO_DIALOGUE);
        assertThat(report.audioSpanSeconds()).isEqualTo(3.0);
        assertThat(report.suggestedDurationSeconds()).isEqualTo(4);
    }

    @Test
    @DisplayName("silent beats are ignored rather than counted as fitting")
    void zeroLengthBeatsAreSkipped() {
        Report report = evaluate(5, 24, new BeatSpan(0, 0, 0, true), measured(1, 1.0, 3.0));

        assertThat(report.beats()).hasSize(1);
        assertThat(report.audioSpanSeconds()).isEqualTo(4.0);
    }

    @Test
    @DisplayName("snapping moves a time to a frame boundary, in the direction asked for")
    void snapping() {
        assertThat(DialogueFitMath.snapUpToFrame(1.37, 24)).isCloseTo(1.375, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(DialogueFitMath.snapDownToFrame(1.37, 24)).isCloseTo(1.3333333, org.assertj.core.data.Offset.offset(1e-6));
        // Already on a boundary: neither direction moves it.
        assertThat(DialogueFitMath.snapUpToFrame(1.5, 24)).isEqualTo(1.5);
        assertThat(DialogueFitMath.snapDownToFrame(1.5, 24)).isEqualTo(1.5);
    }

    @Test
    @DisplayName("an overrun bigger than the shot may grow asks for a rewrite, not for seconds")
    void beyondTheAllowanceNeedsARewrite() {
        // A 5s shot carrying 12.2s of speech. Extending would work arithmetically -- 13s is under the
        // 10s... no, past it -- but even under a generous ceiling the cost is the objection: clips are
        // billed per second, so tripling a shot to fit its line is a different shot, not a fix.
        Report report = DialogueFitMath.evaluate(5, 30,
                List.of(measured(0, 0, 12.213696)), TAIL, MIN_SHOT, 20, 2);

        assertThat(report.verdict()).isEqualTo(Verdict.NEEDS_REWRITE);
        assertThat(report.verdict().needsAttention()).isTrue();
        // It may still take the two seconds it is allowed, which makes the rewrite gentler: the line
        // is retimed against 7s rather than against the 5s it was planned at.
        assertThat(report.allowedDurationSeconds()).isEqualTo(7);
        assertThat(report.suggestedDurationSeconds()).isEqualTo(7);
        assertThat(report.suggestedTargetAudioSeconds()).isCloseTo(6.6, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    @DisplayName("an overrun inside the allowance is just a couple of seconds, so extending is offered")
    void withinTheAllowanceExtends() {
        // shot-01-003's real shape: 3s planned, 4.32s of audio, needs 5s -- exactly the +2s allowed.
        Report report = DialogueFitMath.evaluate(3, 30,
                List.of(measured(0, 0, 4.318912)), TAIL, MIN_SHOT, 15, 2);

        assertThat(report.verdict()).isEqualTo(Verdict.AUDIO_LONGER);
        assertThat(report.allowedDurationSeconds()).isEqualTo(5);
        assertThat(report.suggestedDurationSeconds()).isEqualTo(5);
    }

    @Test
    @DisplayName("the allowance never lets a shot past what the model will generate")
    void allowanceIsCappedByTheModel() {
        Report report = DialogueFitMath.evaluate(9, 24,
                List.of(measured(0, 0, 20.0)), TAIL, MIN_SHOT, 10, 5);

        // 9 + 5 would be 14, but the model stops at 10.
        assertThat(report.allowedDurationSeconds()).isEqualTo(10);
        assertThat(report.verdict()).isEqualTo(Verdict.UNFITTABLE);
    }
}
