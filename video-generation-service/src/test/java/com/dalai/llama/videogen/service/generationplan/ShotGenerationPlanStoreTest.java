package com.dalai.llama.videogen.service.generationplan;

import com.dalai.llama.videogen.domain.ShotActionKind;
import com.dalai.llama.videogen.domain.entity.ShotGenerationPlan;
import com.dalai.llama.videogen.domain.entity.ShotGenerationPlanCoverage;
import com.dalai.llama.videogen.dto.generationplan.RequiredActionView;
import com.dalai.llama.videogen.repository.ShotGenerationPlanActionRepository;
import com.dalai.llama.videogen.repository.ShotGenerationPlanCoverageRepository;
import com.dalai.llama.videogen.repository.ShotGenerationPlanIntervalRepository;
import com.dalai.llama.videogen.repository.ShotGenerationPlanRepository;
import com.dalai.llama.videogen.repository.ShotGenerationPlanRiskRepository;
import com.dalai.llama.videogen.service.VideoGenException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The rules that keep the creator's own decisions safe from the AI steps around them. */
class ShotGenerationPlanStoreTest {

    private final ShotGenerationPlanRepository plans = mock(ShotGenerationPlanRepository.class);
    private final ShotGenerationPlanActionRepository actions = mock(ShotGenerationPlanActionRepository.class);
    private final ShotGenerationPlanCoverageRepository coverage = mock(ShotGenerationPlanCoverageRepository.class);
    private final ShotGenerationPlanRiskRepository risks = mock(ShotGenerationPlanRiskRepository.class);
    private final ShotGenerationPlanIntervalRepository intervals = mock(ShotGenerationPlanIntervalRepository.class);
    private final ShotGenerationPlanStore store = new ShotGenerationPlanStore(plans, actions, coverage, risks, intervals);

    private ShotGenerationPlan plan;

    @BeforeEach
    void setUp() {
        plan = ShotGenerationPlan.builder().planId(UUID.randomUUID()).modelId("m").draftRevision(2).build();
        when(plans.findById(plan.getPlanId())).thenReturn(Optional.of(plan));
        when(plans.save(any())).thenAnswer(call -> call.getArgument(0));
    }

    private static GenerationPlanLlm.AssessmentAnswer recommends(int seconds) {
        return new GenerationPlanLlm.AssessmentAnswer(true, new BigDecimal("4.5"), seconds, 24, "why",
                List.of(new GenerationPlanLlm.CoverageAnswer("OPEN", BigDecimal.ZERO, BigDecimal.ONE, null)), List.of("blur"));
    }

    private static final List<RequiredActionView> ACTIONS = List.of(
            new RequiredActionView("OPEN", ShotActionKind.OPENING_STATE, "open", null, null),
            new RequiredActionView("END", ShotActionKind.ENDING_STATE, "end", null, "OPEN"));

    @Test
    void aDraftSavedOverAnotherTabsEditIsRefusedRatherThanSilentlyLost() {
        assertThatThrownBy(() -> store.saveDraft(plan.getPlanId(), "my edit", 1))
                .isInstanceOf(VideoGenException.class)
                .hasMessageContaining("changed somewhere else");
        assertThat(plan.getUserEditedPrompt()).isNull();
    }

    @Test
    void aDraftOnTheCurrentRevisionIsSavedSeparatelyFromTheRecommendation() {
        plan.setAiRecommendedPrompt("AI text");

        store.saveDraft(plan.getPlanId(), "my edit", 2);

        assertThat(plan.getUserEditedPrompt()).isEqualTo("my edit");
        assertThat(plan.getAiRecommendedPrompt()).isEqualTo("AI text");
        assertThat(plan.getDraftRevision()).isEqualTo(3);
    }

    @Test
    void reanalysingKeepsTheCreatorsChosenSettings() {
        plan.setGenerationDurationSeconds(8);
        plan.setGenerationFps(24);

        store.saveAssessment(plan.getPlanId(), "shot-1", "m", null, ACTIONS, recommends(6));

        assertThat(plan.getRecommendedDurationSeconds()).isEqualTo(6);
        assertThat(plan.getGenerationDurationSeconds()).isEqualTo(8);
    }

    @Test
    void aFirstAnalysisPreselectsTheRecommendationAndClearsTheOldTimeline() {
        plan.setTimelineDurationSeconds(10);

        store.saveAssessment(plan.getPlanId(), "shot-1", "m", null, ACTIONS, recommends(6));

        assertThat(plan.getGenerationDurationSeconds()).isEqualTo(6);
        assertThat(plan.getGenerationFps()).isEqualTo(24);
        // Its identifiers belonged to the old action list.
        assertThat(plan.getTimelineDurationSeconds()).isNull();
        verify(intervals).deleteByPlanId(plan.getPlanId());
    }

    @Test
    @SuppressWarnings("unchecked")
    void coverageIsStoredAsAnsweredSoValidationCanNameWhatWasWrong() {
        store.saveAssessment(plan.getPlanId(), "shot-1", "m", null, ACTIONS, recommends(6));

        ArgumentCaptor<List<ShotGenerationPlanCoverage>> saved = ArgumentCaptor.forClass(List.class);
        verify(coverage).saveAll(saved.capture());
        assertThat(saved.getValue()).singleElement().satisfies(row -> {
            assertThat(row.getActionId()).isEqualTo("OPEN");
            // An unstated "preserved" is not read as a refusal.
            assertThat(row.getPreserved()).isTrue();
        });
    }
}
