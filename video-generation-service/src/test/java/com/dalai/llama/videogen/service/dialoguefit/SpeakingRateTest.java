package com.dalai.llama.videogen.service.dialoguefit;

import org.assertj.core.data.Offset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The speaking rate, checked against the eight real takes in the live project.
 *
 * <p>Every pair below is a measurement: the character count of the text that was synthesized, from
 * {@code cloned_voice_audio.dialogue_text}, and the length of the resulting audio from ffprobe.
 * Hindi, Devanagari, one TTS voice.
 */
class SpeakingRateTest {

    /** characters, measured seconds -- the whole project, 2026-09-15. */
    private static final List<SpeakingRate.Take> PROJECT_TAKES = List.of(
            new SpeakingRate.Take(68, 4.318912),    // shot-01-003
            new SpeakingRate.Take(158, 12.213696),  // shot-01-005
            new SpeakingRate.Take(118, 9.195102),   // shot-01-006
            new SpeakingRate.Take(119, 8.684263),   // shot-01-006
            new SpeakingRate.Take(123, 9.241542),   // shot-01-006
            new SpeakingRate.Take(145, 11.842177),  // shot-01-008
            new SpeakingRate.Take(99, 7.662585),    // shot-01-011
            new SpeakingRate.Take(74, 6.315828));   // shot-01-013

    private static final double CONFIGURED_DEFAULT = 14;

    @Test
    @DisplayName("the configured default is too fast, and that error hides overruns")
    void configuredDefaultRunsShort() {
        SpeakingRate configured = SpeakingRate.fromConfiguredCharsPerSecond(CONFIGURED_DEFAULT);
        SpeakingRate measured = SpeakingRate.fromProject(PROJECT_TAKES, CONFIGURED_DEFAULT);

        // 14 chars/sec configured against roughly 13.2 actually measured.
        assertThat(configured.charsPerSecond()).isEqualTo(14);
        assertThat(measured.charsPerSecond()).isBetween(12.0, 13.5);

        // Which means the default predicts SHORTER than the truth for a given line -- the one
        // direction the error must not go. A check built to catch lines that will be cut cannot have
        // a rate that flatters them.
        String line = "x".repeat(145);  // shot-01-008's length; it really runs 11.84s
        assertThat(configured.secondsFor(line)).isLessThan(measured.secondsFor(line));
        assertThat(configured.secondsFor(line)).isLessThan(11.842177);
        assertThat(measured.secondsFor(line)).isCloseTo(11.4, Offset.offset(1.0));
    }

    @Test
    @DisplayName("a line's own take beats every generalisation")
    void perLineRateIsExact() {
        // shot-01-003: 68 characters, 4.318912s measured.
        SpeakingRate rate = SpeakingRate.fromLine(68, 4.318912);

        assertThat(rate.source()).isEqualTo(SpeakingRate.Source.THIS_LINE);
        assertThat(rate.secondsPerChar()).isCloseTo(0.0635, Offset.offset(0.0001));
        // Round-trips exactly, by construction -- nothing about it is generalised.
        assertThat(rate.secondsFor("x".repeat(68))).isCloseTo(4.318912, Offset.offset(1e-9));
    }

    @Test
    @DisplayName("shot-01-003's rewrite budget: 41 characters for the 2.6s target")
    void charBudgetForShot003() {
        SpeakingRate rate = SpeakingRate.fromLine(68, 4.318912);

        // The fit report's target for this shot, snapped to its 30fps grid.
        assertThat(rate.charBudgetFor(2.6)).isEqualTo(41);
        // Which is the same 60% the ratio in the prompt expresses -- the two agree because they come
        // from the same measurement.
        assertThat(41.0 / 68).isCloseTo(2.6 / 4.318912, Offset.offset(0.01));
    }

    @Test
    @DisplayName("the project rate averages seconds-per-char, not chars-per-second")
    void averagedInTheDirectionItIsUsed() {
        SpeakingRate measured = SpeakingRate.fromProject(PROJECT_TAKES, CONFIGURED_DEFAULT);

        double meanSecPerChar = PROJECT_TAKES.stream()
                .mapToDouble(t -> t.seconds() / t.characters()).average().orElseThrow();
        double meanCharsPerSec = PROJECT_TAKES.stream()
                .mapToDouble(t -> t.characters() / t.seconds()).average().orElseThrow();

        assertThat(measured.secondsPerChar()).isCloseTo(meanSecPerChar, Offset.offset(1e-9));
        // The two are genuinely different -- the mean of reciprocals is not the reciprocal of the
        // mean -- and inverting the convenient one would bias every estimate short, which is the
        // direction that hides overruns.
        assertThat(1.0 / meanCharsPerSec).isLessThan(meanSecPerChar);
    }

    @Test
    @DisplayName("no takes yet falls back to the configured rate and says so")
    void fallsBackWhenNothingMeasured() {
        SpeakingRate rate = SpeakingRate.fromProject(List.of(), CONFIGURED_DEFAULT);

        assertThat(rate.source()).isEqualTo(SpeakingRate.Source.CONFIGURED);
        assertThat(rate.source().fromAudio()).isFalse();
        assertThat(rate.charsPerSecond()).isEqualTo(14);
    }

    @Test
    @DisplayName("unusable takes are ignored rather than skewing the rate toward zero")
    void unusableTakesIgnored() {
        SpeakingRate rate = SpeakingRate.fromProject(List.of(
                new SpeakingRate.Take(68, 4.318912),
                new SpeakingRate.Take(0, 5),      // no text
                new SpeakingRate.Take(100, 0)),   // unmeasurable audio
                CONFIGURED_DEFAULT);

        assertThat(rate.source()).isEqualTo(SpeakingRate.Source.PROJECT);
        assertThat(rate.secondsPerChar()).isCloseTo(4.318912 / 68, Offset.offset(1e-9));
    }

    @Test
    @DisplayName("measured rates beat the default on every real take")
    void measuredBeatsDefaultAcrossTheProject() {
        SpeakingRate configured = SpeakingRate.fromConfiguredCharsPerSecond(CONFIGURED_DEFAULT);
        SpeakingRate measured = SpeakingRate.fromProject(PROJECT_TAKES, CONFIGURED_DEFAULT);

        double configuredError = 0;
        double measuredError = 0;
        for (SpeakingRate.Take take : PROJECT_TAKES) {
            String line = "x".repeat(take.characters());
            configuredError += Math.abs(configured.secondsFor(line) - take.seconds());
            measuredError += Math.abs(measured.secondsFor(line) - take.seconds());
        }

        assertThat(measuredError).isLessThan(configuredError);
    }
}
