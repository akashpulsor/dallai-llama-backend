package com.dalai.llama.critic.service.critique;

import com.dalai.llama.critic.domain.CriticRole;
import com.dalai.llama.critic.domain.CritiqueSeverity;
import com.dalai.llama.critic.domain.CritiqueVerdict;
import com.dalai.llama.critic.domain.PlanSnapshotKind;
import com.dalai.llama.critic.domain.entity.CritiqueFeedback;
import com.dalai.llama.critic.domain.entity.CritiqueFinding;
import com.dalai.llama.critic.domain.entity.CritiqueSession;
import com.dalai.llama.critic.dto.CritiqueFindingView;
import com.dalai.llama.critic.dto.CritiqueRequest;
import com.dalai.llama.critic.dto.CritiqueResult;
import com.dalai.llama.critic.dto.shotcontext.ShotContext;
import com.dalai.llama.critic.repository.CritiqueFindingRepository;
import com.dalai.llama.critic.repository.CritiqueSessionRepository;
import com.dalai.llama.critic.service.CritiqueThoughtService;
import com.dalai.llama.critic.service.SimilarFeedbackService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The pre-flight harness's core loop: IDEA already exists as a {@code ShotContext} by the time
 * this runs -- {@code critique()} runs every registered {@link ShotCritic} (currently DIRECTOR,
 * DP, PRODUCTION_DESIGN), and only if a P1 (blocking) finding survives does it invoke {@link
 * RevisionPlannerService} once. Deliberately not a loop: one revision attempt, then either PASS
 * (revision resolved it) or NEEDS_HUMAN_REVIEW (escalate to a person) -- never retried
 * automatically. Every step is also written to {@link CritiqueThoughtService} so a caller can
 * show the harness's complete thought process, not just the final verdict.
 */
@Service
public class CritiqueOrchestrator {

    private final List<ShotCritic> critics;
    private final RevisionPlannerService revisionPlannerService;
    private final CritiqueSessionRepository critiqueSessionRepository;
    private final CritiqueFindingRepository critiqueFindingRepository;
    private final CritiqueThoughtService critiqueThoughtService;
    private final PlanSnapshotService planSnapshotService;
    private final SimilarFeedbackService similarFeedbackService;

    public CritiqueOrchestrator(
            List<ShotCritic> critics,
            RevisionPlannerService revisionPlannerService,
            CritiqueSessionRepository critiqueSessionRepository,
            CritiqueFindingRepository critiqueFindingRepository,
            CritiqueThoughtService critiqueThoughtService,
            PlanSnapshotService planSnapshotService,
            SimilarFeedbackService similarFeedbackService
    ) {
        this.critics = critics;
        this.revisionPlannerService = revisionPlannerService;
        this.critiqueSessionRepository = critiqueSessionRepository;
        this.critiqueFindingRepository = critiqueFindingRepository;
        this.critiqueThoughtService = critiqueThoughtService;
        this.planSnapshotService = planSnapshotService;
        this.similarFeedbackService = similarFeedbackService;
    }

    @Transactional
    public CritiqueResult critique(UUID tenantId, CritiqueRequest request) {
        UUID sessionId = UUID.randomUUID();
        ShotContext plan = request.shotContext();

        critiqueThoughtService.log(tenantId, sessionId, "PRE_FLIGHT_STARTED",
                "Running pre-flight review for shot " + plan.shotRef());

        String querySummary = plan.narrative() == null ? plan.shotRef() : String.valueOf(plan.narrative().scriptLine());
        List<CritiqueFeedback> similarFeedback = similarFeedbackService.findSimilar(tenantId, querySummary, 3);
        if (!similarFeedback.isEmpty()) {
            critiqueThoughtService.log(tenantId, sessionId, "SIMILAR_FEEDBACK_FOUND",
                    "Found " + similarFeedback.size() + " similar past human feedback item(s) for this tenant"
                            + " (not yet injected into critic prompts -- retrieval-only in this pass)");
        }

        List<RoledFinding> allFindings = new ArrayList<>();
        List<CriticFindingItem> hardConstraintFindings = HardConstraintCheck.run(plan);
        critiqueThoughtService.log(tenantId, sessionId, "HARD_CONSTRAINTS_CHECKED",
                "Level 0 deterministic check raised " + hardConstraintFindings.size() + " blocking finding(s)");
        hardConstraintFindings.forEach(item -> allFindings.add(new RoledFinding(CriticRole.HARD_CONSTRAINTS, item)));

        for (ShotCritic critic : critics) {
            List<CriticFindingItem> items = critic.critique(tenantId, plan);
            long blocking = items.stream().filter(i -> SeverityParser.parse(i.severity()) == CritiqueSeverity.P1).count();
            critiqueThoughtService.log(tenantId, sessionId, critic.role() + "_CRITIQUED",
                    critic.role() + " raised " + items.size() + " finding(s), " + blocking + " blocking");
            items.forEach(item -> allFindings.add(new RoledFinding(critic.role(), item)));
        }

        boolean hasBlockingFinding = allFindings.stream()
                .anyMatch(f -> SeverityParser.parse(f.item().severity()) == CritiqueSeverity.P1);

        ShotContext revisedPlan = null;
        RevisionPlanResponse revision = null;
        CritiqueVerdict verdict;
        if (!hasBlockingFinding) {
            verdict = CritiqueVerdict.PASS;
            critiqueThoughtService.log(tenantId, sessionId, "VALIDATION_GATE", "No blocking findings -- plan passes as-is");
        } else {
            critiqueThoughtService.log(tenantId, sessionId, "REVISION_STARTED",
                    "Blocking finding(s) present -- invoking revision planner");
            try {
                revision = revisionPlannerService.revise(tenantId, plan, toRevisionInput(allFindings));
                revisedPlan = revision.revisedPlan();
                verdict = CritiqueVerdict.PASS;
                critiqueThoughtService.log(tenantId, sessionId, "REVISION_COMPLETED",
                        "Revision planner produced an updated shot plan");
                if (revision.decompositionRecommended()) {
                    critiqueThoughtService.log(tenantId, sessionId, "DECOMPOSITION_RECOMMENDED",
                            "Revision planner recommends splitting into " + revision.suggestedShotCount()
                                    + " shots: " + revision.decompositionReason());
                }
            } catch (RuntimeException ex) {
                verdict = CritiqueVerdict.NEEDS_HUMAN_REVIEW;
                critiqueThoughtService.log(tenantId, sessionId, "REVISION_FAILED",
                        "Revision planner could not resolve blocking finding(s): " + ex.getMessage());
            }
        }

        persistSession(tenantId, request, sessionId, plan, revisedPlan, verdict);
        List<CritiqueFindingView> findingViews = persistFindings(sessionId, allFindings);

        critiqueThoughtService.log(tenantId, sessionId, "VALIDATION_GATE", "Verdict: " + verdict);

        boolean decompositionRecommended = revision != null && revision.decompositionRecommended();
        return new CritiqueResult(sessionId, verdict, findingViews, revisedPlan,
                decompositionRecommended,
                decompositionRecommended ? revision.suggestedShotCount() : null,
                decompositionRecommended ? revision.decompositionReason() : null);
    }

    private void persistSession(UUID tenantId, CritiqueRequest request, UUID sessionId, ShotContext original,
                                 ShotContext revised, CritiqueVerdict verdict) {
        critiqueSessionRepository.save(CritiqueSession.builder()
                .id(sessionId)
                .tenantId(tenantId)
                .projectId(request.projectId())
                .shotId(request.shotId())
                .verdict(verdict)
                .createdAt(OffsetDateTime.now())
                .build());
        planSnapshotService.persist(sessionId, PlanSnapshotKind.ORIGINAL, original);
        if (revised != null) {
            planSnapshotService.persist(sessionId, PlanSnapshotKind.REVISED, revised);
        }
    }

    private List<CritiqueFindingView> persistFindings(UUID sessionId, List<RoledFinding> findings) {
        OffsetDateTime now = OffsetDateTime.now();
        return findings.stream()
                .map(f -> {
                    CritiqueSeverity severity = SeverityParser.parse(f.item().severity());
                    CritiqueFinding saved = critiqueFindingRepository.save(CritiqueFinding.builder()
                            .sessionId(sessionId)
                            .role(f.role())
                            .observation(f.item().observation())
                            .risk(f.item().risk())
                            .cause(f.item().cause())
                            .correction(f.item().correction())
                            .severity(severity)
                            .createdAt(now)
                            .build());
                    return new CritiqueFindingView(saved.getRole(), saved.getObservation(), saved.getRisk(),
                            saved.getCause(), saved.getCorrection(), saved.getSeverity());
                })
                .collect(Collectors.toList());
    }

    private List<FindingForRevision> toRevisionInput(List<RoledFinding> findings) {
        return findings.stream()
                .map(f -> new FindingForRevision(f.role().name(), f.item().observation(), f.item().risk(),
                        f.item().cause(), f.item().correction(), f.item().severity()))
                .collect(Collectors.toList());
    }

    private record RoledFinding(CriticRole role, CriticFindingItem item) {
    }
}
