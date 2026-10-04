package com.dalai.llama.preprod.service.music;

import com.dalai.llama.preprod.dto.music.GlobalMusicIdentity;
import com.dalai.llama.preprod.dto.music.MusicPlan;
import com.dalai.llama.preprod.dto.music.MusicSection;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the model is actually told. These assert the instructions that exist to stop a music
 * model returning several unrelated pieces -- the failure this whole feature was built to fix.
 */
class MasterMusicPromptComposerTest {

    private static final GlobalMusicIdentity IDENTITY = new GlobalMusicIdentity(
            "modern Indian cinematic", null, "warm and trustworthy", 90, "D major", "4/4",
            List.of("piano", "bansuri"), List.of("warm strings"), null,
            "rising three-note motif", "represents trust and resolution", null, null,
            "minimal commercial film score", new GlobalMusicIdentity.EnergyRange(0.2, 0.7),
            "Raag Yaman", "ascending Ni Re Ga, resolving to Sa", "Keherwa, 8 beats");

    private static MusicSection section(double start, double end, String mood, String transition, String dialogue) {
        return new MusicSection(start, end, "beat", mood, 0.4, 0.3, "sparse piano",
                List.of("piano"), "state the motif softly", "neutral harmony", "minimal movement",
                transition, dialogue, List.of());
    }

    private static String compose(List<MusicSection> sections, double total) {
        MusicPlan plan = new MusicPlan(IDENTITY, sections, null,
                "Resolve the motif on a settled chord.", total);
        return MasterMusicPromptComposer.compose(plan, sections, total);
    }

    @Test
    void statesTheIdentityOnceAndAsksForOneContinuousPiece() {
        String prompt = compose(List.of(section(0, 9, "curious", "Opening section", null),
                section(9, 30, "relief", "Open the arrangement", null)), 30);

        assertThat(prompt)
                .contains("continuous 30-second instrumental")
                .contains("modern Indian cinematic")
                .contains("90 BPM")
                .contains("D major")
                .contains("one continuously composed score, not separate tracks stitched together");
        // The palette is listed once, up front -- not restated per section, which is what makes
        // a model treat sections as separate briefs.
        assertThat(prompt.split("piano, bansuri, warm strings", -1)).hasSize(2);
    }

    @Test
    void tellsEverySectionAfterTheFirstNotToRestart() {
        String prompt = compose(List.of(
                section(0, 9, "curious", "Opening section", null),
                section(9, 14, "tension", "Let the harmony darken", null),
                section(14, 30, "relief", "Open the arrangement", null)), 30);

        // Once per continuation section, never on the opening one.
        assertThat(prompt.split("Do not restart the music", -1)).hasSize(3);
    }

    @Test
    void asksForAnEndingComposedForTheExactDurationRatherThanACut() {
        String prompt = compose(List.of(section(0, 37.4, "warm", "Opening section", null)), 37.4);

        assertThat(prompt)
                .contains("Resolve the motif on a settled chord.")
                .contains("intentional, resolved ending exactly at 37.4 seconds");
    }

    @Test
    void rendersWholeSecondsWithoutADecimalTail() {
        // "30 seconds" reads as an instruction; "30.0 seconds" reads like a tolerance.
        assertThat(compose(List.of(section(0, 30, "warm", "Opening section", null)), 30))
                .contains("30-second").doesNotContain("30.0-second");
    }

    @Test
    void carriesDialogueTreatmentThroughAsArrangementDensity() {
        String prompt = compose(List.of(
                section(0, 20, "curious", "Opening section", "Keep the arrangement sparse beneath dialogue")), 20);

        assertThat(prompt).contains("Keep the arrangement sparse beneath dialogue");
    }

    @Test
    void alwaysDemandsInstrumentalBecauseAScoreSitsUnderDialogue() {
        assertThat(compose(List.of(section(0, 12, "warm", "Opening section", null)), 12))
                .contains("Instrumental only, no vocals");
    }

    @Test
    void keepsTheMotifSingularAcrossSections() {
        String prompt = compose(List.of(
                section(0, 9, "curious", "Opening section", null),
                section(9, 30, "confident", "Continue seamlessly", null)), 30);

        assertThat(prompt).contains("sections may change how it is treated, never replace it");
    }

    @Test
    void buildsTheMelodyOnTheRagaAndItsTaal() {
        assertThat(compose(List.of(section(0, 30, "warm", "Opening section", null)), 30))
                .contains("Base the melody on Raag Yaman (ascending Ni Re Ga, resolving to Sa)")
                .contains("over Keherwa, 8 beats");
    }

    @Test
    void neverExceedsTheMusicModelsPromptLimitAndKeepsTheEnding() {
        // A 60s film planned as many wordy sections composed to ~6,000 characters, which
        // ElevenLabs refuses outright.
        String wordy = "layered strings, tabla, santoor and bansuri weaving around the motif while the pads swell ".repeat(3);
        List<MusicSection> sections = IntStream.range(0, 12)
                .mapToObj(i -> new MusicSection(i * 5.0, i * 5.0 + 5, "beat", "mood " + i, 0.4, 0.3, wordy,
                        List.of("piano"), wordy, wordy, "minimal", wordy, wordy, List.of()))
                .toList();

        String prompt = compose(sections, 60);

        assertThat(prompt.length()).isLessThanOrEqualTo(MasterMusicPromptComposer.MAX_PROMPT_CHARS);
        assertThat(prompt)
                .contains("Base the melody on Raag Yaman")
                .contains("55-60 seconds")
                .endsWith("resolved ending exactly at 60 seconds.");
        assertThat(prompt.split("Do not restart the music", -1)).hasSize(12);
    }

    @Test
    void aPromptThatFitsIsLeftWhole() {
        String prompt = compose(List.of(section(0, 30, "warm", "Opening section", "Sparse under the line")), 30);

        assertThat(prompt).contains("neutral harmony").contains("Sparse under the line").doesNotContain("…");
    }
}
