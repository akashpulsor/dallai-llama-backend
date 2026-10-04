package com.dalai.llama.videogen.service.generationplan;

import com.dalai.llama.videogen.domain.entity.ShotGenerationPlan;
import com.dalai.llama.videogen.domain.entity.ShotGenerationPlanAction;
import com.dalai.llama.videogen.domain.entity.ShotGenerationPlanCoverage;
import com.dalai.llama.videogen.domain.entity.ShotGenerationPlanInterval;
import com.dalai.llama.videogen.domain.entity.ShotGenerationPlanRisk;
import com.dalai.llama.videogen.dto.generationplan.RequiredActionView;
import com.dalai.llama.videogen.repository.ShotGenerationPlanActionRepository;
import com.dalai.llama.videogen.repository.ShotGenerationPlanCoverageRepository;
import com.dalai.llama.videogen.repository.ShotGenerationPlanIntervalRepository;
import com.dalai.llama.videogen.repository.ShotGenerationPlanRepository;
import com.dalai.llama.videogen.repository.ShotGenerationPlanRiskRepository;
import com.dalai.llama.videogen.service.VideoGenException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Persistence for the video studio. Every write is one short transaction, and none of them spans a
 * model call: the LLM answers first, then the result is written in one go -- a plan never holds a
 * database connection while it waits on a model.
 */
@Component
@RequiredArgsConstructor
public class ShotGenerationPlanStore {

    private final ShotGenerationPlanRepository planRepository;
    private final ShotGenerationPlanActionRepository actionRepository;
    private final ShotGenerationPlanCoverageRepository coverageRepository;
    private final ShotGenerationPlanRiskRepository riskRepository;
    private final ShotGenerationPlanIntervalRepository intervalRepository;

    /** A plan and its child rows, read together. */
    public record PlanState(ShotGenerationPlan plan,
                            List<ShotGenerationPlanAction> actions,
                            List<ShotGenerationPlanCoverage> coverage,
                            List<ShotGenerationPlanRisk> risks,
                            List<ShotGenerationPlanInterval> intervals) {
    }

    @Transactional(readOnly = true)
    public Optional<PlanState> find(UUID tenantId, UUID shotId) {
        return planRepository.findByTenantIdAndShotId(tenantId, shotId).map(this::state);
    }

    @Transactional(readOnly = true)
    public PlanState require(UUID tenantId, UUID shotId) {
        return find(tenantId, shotId).orElseThrow(() -> VideoGenException.badRequest(
                "Analyse this shot first -- there is no generation plan for it yet."));
    }

    /**
     * Creates the plan if the shot has none, with the actions extracted from its plan. An existing
     * plan is returned untouched: its actions are the ones every later step was checked against,
     * and only re-analysing replaces them.
     */
    @Transactional
    public PlanState ensure(UUID tenantId, UUID projectId, UUID shotId, String shotRef, String modelId,
                            BigDecimal plannedDurationSeconds, List<RequiredActionView> actions) {
        Optional<ShotGenerationPlan> existing = planRepository.findByTenantIdAndShotId(tenantId, shotId);
        if (existing.isPresent()) {
            return state(existing.get());
        }
        OffsetDateTime now = OffsetDateTime.now();
        ShotGenerationPlan plan = planRepository.save(ShotGenerationPlan.builder()
                .planId(UUID.randomUUID())
                .tenantId(tenantId)
                .projectId(projectId)
                .shotId(shotId)
                .shotRef(shotRef)
                .modelId(modelId)
                .plannedDurationSeconds(plannedDurationSeconds)
                .draftRevision(0)
                .createdAt(now)
                .updatedAt(now)
                .build());
        actionRepository.saveAll(actionRows(plan.getPlanId(), actions));
        return state(plan);
    }

    /**
     * Records an assessment, replacing the plan's actions with the ones it was made against.
     *
     * <p>The timeline goes with them: its identifiers belong to the old action list, so keeping it
     * would let a timeline describe actions the plan no longer numbers that way. The prompt and the
     * creator's draft are kept -- they are work, and the page shows them as older than the analysis.
     *
     * <p>The creator's selected settings are kept if they made a selection; only an empty selection
     * takes the recommendation, so re-analysing never silently changes a choice they made.
     */
    @Transactional
    public PlanState saveAssessment(UUID planId, String shotRef, String modelId, BigDecimal plannedDurationSeconds,
                                    List<RequiredActionView> actions, GenerationPlanLlm.AssessmentAnswer answer) {
        ShotGenerationPlan plan = planRepository.findById(planId).orElseThrow();
        boolean modelChanged = !Objects.equals(plan.getModelId(), modelId);
        plan.setShotRef(shotRef);
        plan.setModelId(modelId);
        plan.setPlannedDurationSeconds(plannedDurationSeconds);
        plan.setAssessedAt(OffsetDateTime.now());
        plan.setShorterGenerationSuitable(answer.shorterGenerationSuitable());
        plan.setMinimumViableDurationSeconds(scaled(answer.minimumViableDurationSeconds()));
        plan.setRecommendedDurationSeconds(answer.recommendedDurationSeconds());
        plan.setRecommendedFps(answer.recommendedGenerationFps() == null || answer.recommendedGenerationFps() <= 0
                ? null : answer.recommendedGenerationFps());
        plan.setAssessmentReasoning(answer.reasoning());
        if (modelChanged || plan.getGenerationDurationSeconds() == null) {
            plan.setGenerationDurationSeconds(plan.getRecommendedDurationSeconds());
            plan.setGenerationFps(plan.getRecommendedFps());
        }
        plan.setTimelineDurationSeconds(null);
        plan.setTimelineFps(null);
        plan.setTimelineBuiltAt(null);
        plan.setUpdatedAt(OffsetDateTime.now());
        planRepository.save(plan);

        actionRepository.deleteByPlanId(planId);
        coverageRepository.deleteByPlanId(planId);
        riskRepository.deleteByPlanId(planId);
        intervalRepository.deleteByPlanId(planId);
        actionRepository.saveAll(actionRows(planId, actions));

        List<ShotGenerationPlanCoverage> coverage = new ArrayList<>();
        List<GenerationPlanLlm.CoverageAnswer> answered = answer.actionCoverage() == null ? List.of() : answer.actionCoverage();
        for (int i = 0; i < answered.size(); i++) {
            GenerationPlanLlm.CoverageAnswer entry = answered.get(i);
            coverage.add(ShotGenerationPlanCoverage.builder()
                    .planId(planId)
                    .ordinal(i)
                    .actionId(entry.actionId() == null ? "?" : truncate(entry.actionId(), 64))
                    .startSeconds(orZero(entry.startSeconds()))
                    .endSeconds(orZero(entry.endSeconds()))
                    .preserved(!Boolean.FALSE.equals(entry.preserved()))
                    .build());
        }
        coverageRepository.saveAll(coverage);

        List<String> risks = answer.risks() == null ? List.of() : answer.risks();
        List<ShotGenerationPlanRisk> riskRows = new ArrayList<>();
        for (int i = 0; i < risks.size(); i++) {
            if (risks.get(i) != null && !risks.get(i).isBlank()) {
                riskRows.add(ShotGenerationPlanRisk.builder().planId(planId).ordinal(i).risk(risks.get(i)).build());
            }
        }
        riskRepository.saveAll(riskRows);
        return state(plan);
    }

    @Transactional
    public PlanState saveTimeline(UUID planId, int durationSeconds, Integer fps,
                                  List<GenerationPlanLlm.IntervalAnswer> intervals) {
        ShotGenerationPlan plan = planRepository.findById(planId).orElseThrow();
        plan.setTimelineDurationSeconds(durationSeconds);
        plan.setTimelineFps(fps);
        plan.setTimelineBuiltAt(OffsetDateTime.now());
        plan.setUpdatedAt(OffsetDateTime.now());
        planRepository.save(plan);
        intervalRepository.deleteByPlanId(planId);
        List<ShotGenerationPlanInterval> rows = new ArrayList<>();
        for (int i = 0; i < intervals.size(); i++) {
            GenerationPlanLlm.IntervalAnswer interval = intervals.get(i);
            rows.add(ShotGenerationPlanInterval.builder()
                    .planId(planId)
                    .ordinal(i)
                    .startSeconds(orZero(interval.startSeconds()))
                    .endSeconds(orZero(interval.endSeconds()))
                    .actionId(interval.actionId() == null ? "?" : truncate(interval.actionId(), 64))
                    .action(interval.action() == null ? "" : interval.action())
                    .subjectState(interval.subjectState())
                    .cameraBehavior(interval.cameraBehavior())
                    .holdRequired(Boolean.TRUE.equals(interval.holdRequired()))
                    .build());
        }
        intervalRepository.saveAll(rows);
        return state(plan);
    }

    /** Applies a change to the plan row alone and stamps it. */
    @Transactional
    public PlanState update(UUID planId, Consumer<ShotGenerationPlan> change) {
        ShotGenerationPlan plan = planRepository.findById(planId).orElseThrow();
        change.accept(plan);
        plan.setUpdatedAt(OffsetDateTime.now());
        planRepository.save(plan);
        return state(plan);
    }

    /**
     * Saves the creator's draft only if it was written on the revision they saw. Two tabs editing
     * one shot would otherwise each save over the other, and the loser's work would vanish without
     * anyone being told.
     */
    @Transactional
    public PlanState saveDraft(UUID planId, String prompt, int expectedRevision) {
        ShotGenerationPlan plan = planRepository.findById(planId).orElseThrow();
        if (plan.getDraftRevision() != expectedRevision) {
            throw VideoGenException.conflict(
                    "This prompt was changed somewhere else since you opened it. Reload to see that version before saving.");
        }
        plan.setUserEditedPrompt(prompt);
        plan.setDraftRevision(plan.getDraftRevision() + 1);
        plan.setDraftSavedAt(OffsetDateTime.now());
        plan.setUpdatedAt(OffsetDateTime.now());
        planRepository.save(plan);
        return state(plan);
    }

    private PlanState state(ShotGenerationPlan plan) {
        UUID planId = plan.getPlanId();
        return new PlanState(plan,
                actionRepository.findByPlanIdOrderByOrdinalAsc(planId),
                coverageRepository.findByPlanIdOrderByOrdinalAsc(planId),
                riskRepository.findByPlanIdOrderByOrdinalAsc(planId),
                intervalRepository.findByPlanIdOrderByOrdinalAsc(planId));
    }

    private static List<ShotGenerationPlanAction> actionRows(UUID planId, List<RequiredActionView> actions) {
        List<ShotGenerationPlanAction> rows = new ArrayList<>();
        for (int i = 0; i < actions.size(); i++) {
            RequiredActionView action = actions.get(i);
            rows.add(ShotGenerationPlanAction.builder()
                    .planId(planId)
                    .ordinal(i)
                    .actionId(action.actionId())
                    .kind(action.kind())
                    .description(action.description())
                    .fixedSeconds(action.fixedSeconds())
                    .dependsOnActionId(action.dependsOnActionId())
                    .build());
        }
        return rows;
    }

    private static BigDecimal scaled(BigDecimal value) {
        return value == null ? null : value.setScale(3, RoundingMode.HALF_UP);
    }

    private static BigDecimal orZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO.setScale(3) : value.setScale(3, RoundingMode.HALF_UP);
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
