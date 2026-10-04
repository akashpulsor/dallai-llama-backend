package com.dalai.llama.preprod.service.music;

import com.dalai.llama.preprod.dto.music.GlobalMusicIdentity;
import com.dalai.llama.preprod.dto.music.MusicPlan;
import com.dalai.llama.preprod.dto.music.MusicSection;
import com.dalai.llama.preprod.service.PreProductionException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The timeline guarantees generation depends on. A plan that fails any of these would produce a
 * score with a hole in it, or one that ends in the wrong place -- and generation is billable, so
 * these are checked before a provider is ever called.
 */
class MusicPlanValidatorTest {

    private static final GlobalMusicIdentity IDENTITY = new GlobalMusicIdentity(
            "modern Indian cinematic", null, "warm", 90, "D major", "4/4",
            List.of("piano", "bansuri"), List.of("warm strings"), null,
            "rising three-note melody", "trust and resolution", null, null, null,
            new GlobalMusicIdentity.EnergyRange(0.2, 0.7), null, null, null);

    private static MusicSection section(double start, double end) {
        return new MusicSection(start, end, "beat", "mood", 0.4, 0.3, "arrangement",
                List.of("piano"), "state the motif", null, null, "continue", null, List.of());
    }

    private static MusicPlan plan(List<MusicSection> sections, double total) {
        return new MusicPlan(IDENTITY, sections, "prompt", "resolve on the motif", total);
    }

    @Test
    void acceptsAContiguousTimelineThatCoversTheWholeFilm() {
        MusicPlan p = plan(List.of(section(0, 9), section(9, 14), section(14, 30)), 30);
        assertThatCode(() -> MusicPlanValidator.validate(p, 30)).doesNotThrowAnyException();
    }

    @Test
    void returnsSectionsInTimelineOrderEvenWhenThePlannerEmitsThemShuffled() {
        MusicPlan p = plan(List.of(section(14, 30), section(0, 9), section(9, 14)), 30);
        List<MusicSection> ordered = MusicPlanValidator.validate(p, 30);
        assertThat(ordered).extracting(MusicSection::startTime).containsExactly(0.0, 9.0, 14.0);
    }

    @Test
    void acceptsASingleSectionForAFilmWithOneEmotionalBeat() {
        // A short, single-mood ad legitimately scores as one continuous section. The planner is
        // asked for fewer sections than shots, and one is a valid answer to that.
        MusicPlan p = plan(List.of(section(0, 12)), 12);
        assertThatCode(() -> MusicPlanValidator.validate(p, 12)).doesNotThrowAnyException();
    }

    @Test
    void rejectsAGapBecauseTheMusicWouldAudiblyStop() {
        MusicPlan p = plan(List.of(section(0, 9), section(11, 30)), 30);
        assertThatThrownBy(() -> MusicPlanValidator.validate(p, 30))
                .isInstanceOf(PreProductionException.class)
                .hasMessageContaining("Gap in the score");
    }

    @Test
    void rejectsOverlappingSections() {
        MusicPlan p = plan(List.of(section(0, 12), section(9, 30)), 30);
        assertThatThrownBy(() -> MusicPlanValidator.validate(p, 30))
                .isInstanceOf(PreProductionException.class)
                .hasMessageContaining("overlap");
    }

    @Test
    void rejectsAScoreThatDoesNotStartAtZero() {
        MusicPlan p = plan(List.of(section(2, 30)), 30);
        assertThatThrownBy(() -> MusicPlanValidator.validate(p, 30))
                .isInstanceOf(PreProductionException.class)
                .hasMessageContaining("must start at 0");
    }

    @Test
    void rejectsAScoreThatDoesNotEndAtTheFilmLength() {
        // The ending has to be composed for the real duration, not cut to it.
        MusicPlan p = plan(List.of(section(0, 25)), 30);
        assertThatThrownBy(() -> MusicPlanValidator.validate(p, 30))
                .isInstanceOf(PreProductionException.class)
                .hasMessageContaining("but the video is");
    }

    @Test
    void toleratesSubFrameFloatDriftRatherThanFailingAPaidGeneration() {
        // 20ms of rounding is not a hole in the score; it is how decimals come back from a model.
        MusicPlan p = plan(List.of(section(0, 9.0), section(9.02, 30.01)), 30);
        assertThatCode(() -> MusicPlanValidator.validate(p, 30)).doesNotThrowAnyException();
    }

    @Test
    void rejectsAPlanWithNoIdentityBecauseThereWouldBeNothingHoldingItTogether() {
        MusicPlan p = new MusicPlan(null, List.of(section(0, 30)), "prompt", "resolve", 30.0);
        assertThatThrownBy(() -> MusicPlanValidator.validate(p, 30))
                .isInstanceOf(PreProductionException.class)
                .hasMessageContaining("global musical identity");
    }

    @Test
    void rejectsAnEmptyPlan() {
        assertThatThrownBy(() -> MusicPlanValidator.validate(plan(List.of(), 30), 30))
                .isInstanceOf(PreProductionException.class)
                .hasMessageContaining("no sections");
    }
}
