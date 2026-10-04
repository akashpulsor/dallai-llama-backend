package com.dalai.llama.videogen.service.generationplan;

import com.dalai.llama.videogen.domain.ShotActionKind;
import com.dalai.llama.videogen.dto.generationplan.ActionCoverageView;
import com.dalai.llama.videogen.dto.generationplan.RequiredActionView;
import com.dalai.llama.videogen.dto.generationplan.SourceTemporalActionView;
import com.dalai.llama.videogen.dto.generationplan.ValidationIssueView;
import com.dalai.llama.videogen.dto.generationplan.VideoDurationAssessmentView;
import com.dalai.llama.videogen.dto.generationplan.VideoModelCapabilitiesView;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The structural half of "never delete an action": what can be proven without reading the prose.
 * A model's own claim of full coverage is never trusted -- each of these is a way it has been or
 * could be wrong while saying it was right.
 */
class GenerationPlanValidatorTest {

    private static final VideoModelCapabilitiesView SEEDANCE = new VideoModelCapabilitiesView(
            "bytedance/seedance-2.0/fast", IntStream.rangeClosed(4, 15).boxed().toList(), List.of(24), true);

    /** OPEN, A1 -> A2, D1 (1.8s fixed), END. */
    private static final List<RequiredActionView> ACTIONS = List.of(
            new RequiredActionView("OPEN", ShotActionKind.OPENING_STATE, "Opening state", null, null),
            new RequiredActionView("A1", ShotActionKind.ACTION, "He picks up the spanner.", null, "OPEN"),
            new RequiredActionView("A2", ShotActionKind.ACTION, "He tightens the valve.", null, "A1"),
            new RequiredActionView("D1", ShotActionKind.DIALOGUE, "Ravi says: \"Done.\"", new BigDecimal("1.8"), "OPEN"),
            new RequiredActionView("END", ShotActionKind.ENDING_STATE, "Final state", null, "A2"));

    private static ActionCoverageView cover(String id, String start, String end) {
        return new ActionCoverageView(id, new BigDecimal(start), new BigDecimal(end), true);
    }

    private static VideoDurationAssessmentView assessment(int recommended, Integer fps, List<ActionCoverageView> coverage) {
        return new VideoDurationAssessmentView(true, new BigDecimal("5.5"), recommended, fps, "why", coverage, List.of(), null);
    }

    private static List<ActionCoverageView> fullCoverage() {
        return List.of(cover("OPEN", "0", "0.5"), cover("A1", "0.5", "2"), cover("A2", "2", "4.5"),
                cover("D1", "1", "3.5"), cover("END", "4.5", "6"));
    }

    private static SourceTemporalActionView interval(String start, String end, String id) {
        return new SourceTemporalActionView(new BigDecimal(start), new BigDecimal(end), id, "x", null, null, false);
    }

    private static List<String> codes(List<ValidationIssueView> issues) {
        return issues.stream().map(ValidationIssueView::code).toList();
    }

    // ---------------------------------------------------------------- assessment

    @Test
    void anAssessmentThatTimesEveryActionInOrderPasses() {
        assertThat(GenerationPlanValidator.assessment(ACTIONS, assessment(6, 24, fullCoverage()), SEEDANCE)).isEmpty();
    }

    @Test
    void aMissingActionIsReportedEvenWhenTheModelSaysCoverageIsComplete() {
        List<ActionCoverageView> withoutA2 = new ArrayList<>(fullCoverage());
        withoutA2.removeIf(c -> c.actionId().equals("A2"));

        List<ValidationIssueView> issues = GenerationPlanValidator.assessment(ACTIONS, assessment(6, 24, withoutA2), SEEDANCE);

        assertThat(issues).anySatisfy(issue -> {
            assertThat(issue.code()).isEqualTo("MISSING_ACTION");
            assertThat(issue.actionId()).isEqualTo("A2");
        });
    }

    @Test
    void anActionTheModelInventedIsRejected() {
        List<ActionCoverageView> withExtra = new ArrayList<>(fullCoverage());
        withExtra.add(cover("A9", "1", "2"));

        assertThat(codes(GenerationPlanValidator.assessment(ACTIONS, assessment(6, 24, withExtra), SEEDANCE)))
                .contains("UNKNOWN_ACTION");
    }

    @Test
    void anActionStartingBeforeTheOneItDependsOnBreaksThePlansOrder() {
        List<ActionCoverageView> reordered = List.of(cover("OPEN", "0", "0.5"), cover("A1", "2", "4"),
                cover("A2", "0.5", "2"), cover("D1", "1", "3.5"), cover("END", "4.5", "6"));

        List<ValidationIssueView> issues = GenerationPlanValidator.assessment(ACTIONS, assessment(6, 24, reordered), SEEDANCE);

        assertThat(issues).anySatisfy(issue -> {
            assertThat(issue.code()).isEqualTo("ORDER_VIOLATED");
            assertThat(issue.actionId()).isEqualTo("A2");
        });
    }

    @Test
    void aDurationOrFrameRateTheModelCannotRenderIsRejected() {
        List<String> codes = codes(GenerationPlanValidator.assessment(ACTIONS, assessment(3, 30, fullCoverage()), SEEDANCE));

        assertThat(codes).contains("UNSUPPORTED_DURATION", "UNSUPPORTED_FPS");
    }

    @Test
    void aMeasuredLineSqueezedIntoLessTimeThanItTakesToSayIsReported() {
        List<ActionCoverageView> squeezed = new ArrayList<>(fullCoverage());
        squeezed.replaceAll(c -> c.actionId().equals("D1") ? cover("D1", "1", "2") : c);

        assertThat(codes(GenerationPlanValidator.assessment(ACTIONS, assessment(6, 24, squeezed), SEEDANCE)))
                .contains("FIXED_TIMING_SHORTENED");
    }

    @Test
    void theFinalStateMustBeReachedAtTheRecommendedDuration() {
        List<ActionCoverageView> earlyEnd = new ArrayList<>(fullCoverage());
        earlyEnd.replaceAll(c -> c.actionId().equals("END") ? cover("END", "4.5", "5") : c);

        assertThat(codes(GenerationPlanValidator.assessment(ACTIONS, assessment(6, 24, earlyEnd), SEEDANCE)))
                .contains("ENDING_STATE_NOT_AT_END");
    }

    // ---------------------------------------------------------------- timeline

    private static List<SourceTemporalActionView> continuousTimeline() {
        return List.of(interval("0", "1", "OPEN"), interval("1", "2", "A1"), interval("2", "3", "D1"),
                interval("3", "4", "D1"), interval("4", "5", "A2"), interval("5", "5.5", "END"));
    }

    @Test
    void aContinuousTimelineWithFractionalIntervalsPasses() {
        List<SourceTemporalActionView> sixSeconds = new ArrayList<>(continuousTimeline());
        sixSeconds.set(5, interval("5", "6", "END"));
        assertThat(GenerationPlanValidator.timeline(ACTIONS, sixSeconds, 6)).isEmpty();

        // Half-second boundaries, compared at millisecond precision: 2.50 meets 2.5 exactly.
        List<SourceTemporalActionView> fractional = List.of(interval("0", "1", "OPEN"), interval("1", "2.5", "A1"),
                interval("2.50", "5.0", "D1"), interval("5.0", "5.5", "A2"), interval("5.5", "6", "END"));
        assertThat(GenerationPlanValidator.timeline(ACTIONS, fractional, 6)).isEmpty();
    }

    @Test
    void aTimelineEndingOnAHalfSecondIsShortOfAWholeSecondDuration() {
        assertThat(codes(GenerationPlanValidator.timeline(ACTIONS, continuousTimeline(), 6))).contains("TIMELINE_NOT_TO_END");
    }

    @Test
    void gapsAndOverlapsAreBothReported() {
        List<SourceTemporalActionView> gap = List.of(interval("0", "1", "OPEN"), interval("1.5", "2", "A1"),
                interval("2", "3", "D1"), interval("3", "4", "D1"), interval("4", "5", "A2"), interval("5", "6", "END"));
        List<SourceTemporalActionView> overlap = List.of(interval("0", "1", "OPEN"), interval("0.8", "2", "A1"),
                interval("2", "3", "D1"), interval("3", "4", "D1"), interval("4", "5", "A2"), interval("5", "6", "END"));

        assertThat(codes(GenerationPlanValidator.timeline(ACTIONS, gap, 6))).contains("GAP");
        assertThat(codes(GenerationPlanValidator.timeline(ACTIONS, overlap, 6))).contains("OVERLAP");
    }

    @Test
    void aTimelineMustCoverTheWholeSelectedDurationFromZero() {
        List<SourceTemporalActionView> short4 = List.of(interval("0", "1", "OPEN"), interval("1", "2", "A1"),
                interval("2", "3", "A2"), interval("3", "4", "D1"));

        List<String> codes = codes(GenerationPlanValidator.timeline(ACTIONS, short4, 6));

        assertThat(codes).contains("TIMELINE_NOT_TO_END", "MISSING_ACTION", "ENDING_STATE_NOT_LAST");
    }

    @Test
    void aTimelineThatDropsOrReordersAnActionIsCaught() {
        List<SourceTemporalActionView> reordered = List.of(interval("0", "1", "OPEN"), interval("1", "2", "A2"),
                interval("2", "3", "A1"), interval("3", "4", "D1"), interval("4", "5", "D1"), interval("5", "6", "END"));

        assertThat(GenerationPlanValidator.timeline(ACTIONS, reordered, 6)).anySatisfy(issue -> {
            assertThat(issue.code()).isEqualTo("ORDER_VIOLATED");
            assertThat(issue.actionId()).isEqualTo("A2");
        });
    }

    // ---------------------------------------------------------------- settings

    @Test
    void settingsTheModelCannotRenderAreErrorsAndShorterThanViableIsOnlyAWarning() {
        assertThat(codes(GenerationPlanValidator.settingsErrors(SEEDANCE, 3, 24))).containsExactly("UNSUPPORTED_DURATION");
        assertThat(codes(GenerationPlanValidator.settingsErrors(SEEDANCE, 6, 30))).containsExactly("UNSUPPORTED_FPS");
        assertThat(GenerationPlanValidator.settingsErrors(SEEDANCE, 5, 24)).isEmpty();

        // 5s against a 5.5s minimum: the creator may choose it, and is told why it is risky.
        assertThat(codes(GenerationPlanValidator.settingsWarnings(5, assessment(6, 24, fullCoverage()))))
                .containsExactly("BELOW_MINIMUM_VIABLE");
        assertThat(GenerationPlanValidator.settingsWarnings(6, assessment(6, 24, fullCoverage()))).isEmpty();
    }

    @Test
    void aModelWithAnUnknownFrameRateDoesNotDemandOne() {
        VideoModelCapabilitiesView undeclared = new VideoModelCapabilitiesView("new/model", List.of(3, 4, 5), List.of(), false);

        assertThat(GenerationPlanValidator.settingsErrors(undeclared, 4, null)).isEmpty();
    }

    // ---------------------------------------------------------------- prompt

    @Test
    void anEmptyOrOverLongPromptBlocksGeneration() {
        assertThat(codes(GenerationPlanValidator.promptErrors("  ", 100))).containsExactly("EMPTY_PROMPT");
        assertThat(codes(GenerationPlanValidator.promptErrors("x".repeat(101), 100))).containsExactly("OVER_CHARACTER_LIMIT");
        assertThat(GenerationPlanValidator.promptErrors("x".repeat(100), 100)).isEmpty();
    }

    @Test
    void timingPastTheClipAndPostProductionInstructionsAreWarningsNotErrors() {
        String prompt = "At 0-1s he kneels. At 7s he stands, in slow motion.";

        List<String> codes = codes(GenerationPlanValidator.promptWarnings(prompt, 6));

        assertThat(codes).contains("TIME_BEYOND_DURATION", "POST_PRODUCTION_INSTRUCTION");
        assertThat(codes(GenerationPlanValidator.promptWarnings("He kneels and stands.", 6))).contains("NO_TIMESTAMPS");
    }
}
