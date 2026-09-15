package com.dalai.llama.videogen.service.dialoguefit;

import com.dalai.llama.videogen.service.dialoguefit.DialogueFitMath.BeatSpan;
import com.dalai.llama.videogen.service.dialoguefit.DialogueFitMath.Report;
import com.dalai.llama.videogen.service.dialoguefit.DialogueFitMath.Verdict;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The algorithm run against the real project, with real numbers.
 *
 * <p>Every figure here was taken from the running system on 2026-09-15: the planned durations and
 * frame rates from pre-production's {@code shot} table for project "The Unspoken Question"
 * (49d21309), and the audio lengths by ffprobing the synthesized takes stored in
 * {@code cloned_voice_audio}. Nothing is invented, and nothing is rounded to make a case.
 *
 * <p>What the measurements showed is that this project has the problem in ONE direction only.
 * Every dubbed shot's line is longer than the clip planned for it -- several of them two or three
 * times longer -- and not one shot has audio shorter than its clip. The quiet case the algorithm
 * also handles does not occur here at all. Audio being cut is the whole of the real problem.
 *
 * <p>shot-01-003 is the one that reached a customer: the only completed job with auto-dub
 * ({@code mute_audio=t, dub_succeeded=t}), generated at 3 seconds carrying 4.32 seconds of
 * narration. The dub reports success because the mux did what it was told -- it pinned a 4.32s track
 * to a 3s video and dropped the rest. Nothing in the system recorded that a third of the line was
 * missing.
 */
class DialogueFitProductionShotsTest {

    /** As deployed: {@code video-gen.dialogue-fit.tail-seconds}, min/max shot duration. */
    private static final double TAIL = 0.4;
    private static final int MIN_SHOT = 3;
    private static final int MAX_SHOT = 10;

    /** Every shot in this project is planned at 30fps. */
    private static final int FPS = 30;

    private static Report evaluateSingleBeat(int plannedSeconds, double measuredAudioSeconds) {
        return DialogueFitMath.evaluate(plannedSeconds, FPS,
                List.of(new BeatSpan(0, 0, measuredAudioSeconds, true)), TAIL, MIN_SHOT, MAX_SHOT);
    }

    @Test
    @DisplayName("shot-01-003, the one that shipped cut: 4.32s of narration in a 3s clip")
    void shot003() {
        // "क्या आपको कभी ऐसा लगा है कि जवाब सबके पास हैं, पर आपके लिए कोई नहीं?"
        // Measured: 4.318912s (ffprobe, audio/mpeg, 69 KiB). Planned: 3s at 30fps.
        Report report = evaluateSingleBeat(3, 4.318912);

        assertThat(report.verdict()).isEqualTo(Verdict.AUDIO_LONGER);
        assertThat(report.verdict().needsAttention()).isTrue();

        // 4.318912 + 0.4 tail = 4.718912s, which is 141.57 frames -- so 142 whole frames are needed
        // against the 90 the clip has.
        assertThat(report.requiredSeconds()).isCloseTo(4.718912, Offset.offset(1e-9));
        assertThat(report.slackFrames()).isEqualTo(-52);
        assertThat(report.slackSeconds()).isCloseTo(-52 / 30.0, Offset.offset(1e-9));

        // Option 1: give the shot the seconds the line needs. 5s, not 4 -- 4 would still be short.
        assertThat(report.suggestedDurationSeconds()).isEqualTo(5);
        // Option 2: rephrase to fit the 3s as planned. 2.6s exactly, since 2.6 x 30 = 78 whole frames.
        assertThat(report.suggestedTargetAudioSeconds()).isCloseTo(2.6, Offset.offset(1e-9));
        // Option 3 (go with the original) needs no number: it is the shot exactly as planned.

        // The measurement is what makes this tractable. VIDEO_DIALOGUE_FIT's own migration note
        // recorded this shot as needing "about eighteen" seconds -- a model's guess at a Hindi line,
        // out by a factor of four. A remedy sized from that guess would have been useless.
        assertThat(report.measured()).isTrue();
    }

    @Test
    @DisplayName("shot-01-013: a 2s clip carrying 6.3s of speech -- fixable, but not by resizing")
    void shot013() {
        Report report = evaluateSingleBeat(2, 6.315828);

        assertThat(report.verdict()).isEqualTo(Verdict.AUDIO_LONGER);
        assertThat(report.suggestedDurationSeconds()).isEqualTo(7);
        assertThat(report.suggestedTargetAudioSeconds()).isCloseTo(1.6, Offset.offset(1e-9));
        // Worth reading as a person rather than as a number: the arithmetic says "extend to 7s", and
        // that is true, but it means tripling a closing shot -- and the alternative, saying the same
        // thing in 1.6s instead of 6.3s, is not a rephrase, it is a different line. Neither offered
        // remedy is really right here; the shot list is. The algorithm cannot know that, which is
        // exactly why it presents both options and decides nothing.
        assertThat(report.slackFrames()).isEqualTo(-142);
    }

    @Test
    @DisplayName("shot-01-005 and shot-01-008 are unfittable at a 10s ceiling")
    void unfittableAtATenSecondCeiling() {
        Report shot005 = evaluateSingleBeat(5, 12.213696);
        Report shot008 = evaluateSingleBeat(5, 11.842177);

        // 12.6s and 12.2s needed against a 10s ceiling: extending cannot deliver what it promises,
        // so these are refused at approve unless the creator chooses to go with the original.
        assertThat(shot005.verdict()).isEqualTo(Verdict.UNFITTABLE);
        assertThat(shot008.verdict()).isEqualTo(Verdict.UNFITTABLE);
        assertThat(shot005.suggestedDurationSeconds()).isEqualTo(MAX_SHOT);
        // The only remedy that can work at this ceiling: a line that fits inside the longest clip.
        assertThat(shot005.suggestedTargetAudioSeconds()).isCloseTo(9.6, Offset.offset(1e-9));
    }

    @Test
    @DisplayName("...and extendable at the 15s ceiling now deployed")
    void extendableAtTheDeployedCeiling() {
        // The ceiling is ours, not the provider's: fal.ai's alibaba/wan-3.0-prime documents duration
        // as a free parameter defaulting to 5s and states no maximum. It was raised to 15 so these
        // two shots could grow to hold their lines instead of being blocked.
        int deployedMax = 15;
        Report shot005 = DialogueFitMath.evaluate(5, FPS,
                List.of(new BeatSpan(0, 0, 12.213696, true)), TAIL, MIN_SHOT, deployedMax);
        Report shot008 = DialogueFitMath.evaluate(5, FPS,
                List.of(new BeatSpan(0, 0, 11.842177, true)), TAIL, MIN_SHOT, deployedMax);

        assertThat(shot005.verdict()).isEqualTo(Verdict.AUDIO_LONGER);
        assertThat(shot008.verdict()).isEqualTo(Verdict.AUDIO_LONGER);
        // 12.61s and 12.24s of required audio both round up to a 13-second clip.
        assertThat(shot005.suggestedDurationSeconds()).isEqualTo(13);
        assertThat(shot008.suggestedDurationSeconds()).isEqualTo(13);

        // Raising the ceiling does not make everything fit -- it moves the line. A shot needing more
        // than 15s would still be refused, which is the point of having a ceiling at all.
        Report absurd = DialogueFitMath.evaluate(5, FPS,
                List.of(new BeatSpan(0, 0, 30.0, true)), TAIL, MIN_SHOT, deployedMax);
        assertThat(absurd.verdict()).isEqualTo(Verdict.UNFITTABLE);
    }

    @Test
    @DisplayName("shot-01-011: 7.66s in a 4s clip")
    void shot011() {
        Report report = evaluateSingleBeat(4, 7.662585);

        assertThat(report.verdict()).isEqualTo(Verdict.AUDIO_LONGER);
        assertThat(report.suggestedDurationSeconds()).isEqualTo(9);
        assertThat(report.suggestedTargetAudioSeconds()).isCloseTo(3.6, Offset.offset(1e-9));
    }

    @Test
    @DisplayName("not one shot in the project has audio shorter than its clip")
    void everyShotOverruns() {
        // plannedSeconds -> measured audio, for every synthesized take in the project.
        double[][] shots = {
                {3, 4.318912},   // shot-01-003
                {5, 12.213696},  // shot-01-005
                {4, 9.241542},   // shot-01-006
                {5, 11.842177},  // shot-01-008
                {4, 7.662585},   // shot-01-011
                {2, 6.315828},   // shot-01-013
        };

        for (double[] shot : shots) {
            Report report = evaluateSingleBeat((int) shot[0], shot[1]);
            assertThat(report.slackFrames())
                    .describedAs("shot planned %ss carrying %ss of audio", shot[0], shot[1])
                    .isNegative();
            assertThat(report.verdict())
                    .isIn(Verdict.AUDIO_LONGER, Verdict.UNFITTABLE);
        }
    }
}
