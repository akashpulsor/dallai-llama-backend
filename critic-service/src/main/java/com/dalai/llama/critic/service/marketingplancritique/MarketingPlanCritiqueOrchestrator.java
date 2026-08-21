package com.dalai.llama.critic.service.marketingplancritique;

import com.dalai.llama.critic.domain.CritiqueSeverity;
import com.dalai.llama.critic.domain.CritiqueVerdict;
import com.dalai.llama.critic.domain.MarketingPlanCriticRole;
import com.dalai.llama.critic.domain.PlanSnapshotKind;
import com.dalai.llama.critic.domain.entity.MarketingPlanCritiqueFinding;
import com.dalai.llama.critic.domain.entity.MarketingPlanCritiqueSession;
import com.dalai.llama.critic.dto.marketingplan.MarketingPlanContent;
import com.dalai.llama.critic.dto.marketingplan.MarketingPlanCritiqueFindingView;
import com.dalai.llama.critic.dto.marketingplan.MarketingPlanCritiqueRequest;
import com.dalai.llama.critic.dto.marketingplan.MarketingPlanCritiqueResult;
import com.dalai.llama.critic.repository.MarketingPlanCritiqueFindingRepository;
import com.dalai.llama.critic.repository.MarketingPlanCritiqueSessionRepository;
import com.dalai.llama.critic.service.CritiqueThoughtService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The pre-flight harness's core loop for a marketing plan -- runs every registered {@link
 * MarketingPlanCritic} (STRATEGY, AUDIENCE_FIT, FEASIBILITY), and only if a P1 (blocking) finding
 * survives does it invoke {@link MarketingPlanRevisionPlannerService} once. Marketing-plan
 * sibling of {@code CritiqueOrchestrator}: same one-revision-then-verdict discipline, same
 * complete thought log via the shared {@link CritiqueThoughtService}.
 */
@Service
public class MarketingPlanCritiqueOrchestrator {

    private final List<MarketingPlanCritic> critics;
    private final MarketingPlanRevisionPlannerService revisionPlannerService;
    private final MarketingPlanCritiqueSessionRepository sessionRepository;
    private final MarketingPlanCritiqueFindingRepository findingRepository;
    private final CritiqueThoughtService critiqueThoughtService;
    private final MarketingPlanSnapshotService snapshotService;

    public MarketingPlanCritiqueOrchestrator(
            List<MarketingPlanCritic> critics,
            MarketingPlanRevisionPlannerService revisionPlannerService,
            MarketingPlanCritiqueSessionRepository sessionRepository,
            MarketingPlanCritiqueFindingRepository findingRepository,
            CritiqueThoughtService critiqueThoughtService,
            MarketingPlanSnapshotService snapshotService
    ) {
        this.critics = critics;
        this.revisionPlannerService = revisionPlannerService;
        this.sessionRepository = sessionRepository;
        this.findingRepository = findingRepository;
        this.critiqueThoughtService = critiqueThoughtService;
        this.snapshotService = snapshotService;
    }

    @Transactional
    public MarketingPlanCritiqueResult critique(UUID tenantId, MarketingPlanCritiqueRequest request) {
        UUID sessionId = UUID.randomUUID();
        MarketingPlanContent content = request.content();

        critiqueThoughtService.log(tenantId, sessionId, "PRE_FLIGHT_STARTED",
                "Running pre-flight review for marketing plan " + request.planId());

        List<RoledFinding> allFindings = new ArrayList<>();
        List<MarketingPlanCriticFindingItem> hardConstraintFindings = MarketingPlanHardConstraintCheck.run(content);
        critiqueThoughtService.log(tenantId, sessionId, "HARD_CONSTRAINTS_CHECKED",
                "Level 0 deterministic check raised " + hardConstraintFindings.size() + " blocking finding(s)");
        hardConstraintFindings.forEach(item -> allFindings.add(new RoledFinding(MarketingPlanCriticRole.HARD_CONSTRAINTS, item)));

        for (MarketingPlanCritic critic : critics) {
            List<MarketingPlanCriticFindingItem> items = critic.critique(tenantId, content, request.brandAndAudienceContext());
            long blocking = items.stream().filter(i -> MarketingPlanSeverityParser.parse(i.severity()) == CritiqueSeverity.P1).count();
            critiqueThoughtService.log(tenantId, sessionId, critic.role() + "_CRITIQUED",
                    critic.role() + " raised " + items.size() + " finding(s), " + blocking + " blocking");
            items.forEach(item -> allFindings.add(new RoledFinding(critic.role(), item)));
        }

        boolean hasBlockingFinding = allFindings.stream()
                .anyMatch(f -> MarketingPlanSeverityParser.parse(f.item().severity()) == CritiqueSeverity.P1);

        MarketingPlanContent revisedContent = null;
        CritiqueVerdict verdict;
        if (!hasBlockingFinding) {
            verdict = CritiqueVerdict.PASS;
            critiqueThoughtService.log(tenantId, sessionId, "VALIDATION_GATE", "No blocking findings -- plan passes as-is");
        } else {
            critiqueThoughtService.log(tenantId, sessionId, "REVISION_STARTED",
                    "Blocking finding(s) present -- invoking revision planner");
            try {
                MarketingPlanRevisionResponse revision = revisionPlannerService.revise(
                        tenantId, content, toRevisionInput(allFindings), request.brandAndAudienceContext());
                revisedContent = revision.revisedPlan();
                verdict = CritiqueVerdict.PASS;
                critiqueThoughtService.log(tenantId, sessionId, "REVISION_COMPLETED",
                        "Revision planner produced an updated marketing plan");
            } catch (RuntimeException ex) {
                verdict = CritiqueVerdict.NEEDS_HUMAN_REVIEW;
                critiqueThoughtService.log(tenantId, sessionId, "REVISION_FAILED",
                        "Revision planner could not resolve blocking finding(s): " + ex.getMessage());
            }
        }

        persistSession(tenantId, request, sessionId, content, revisedContent, verdict);
        List<MarketingPlanCritiqueFindingView> findingViews = persistFindings(sessionId, allFindings);

        critiqueThoughtService.log(tenantId, sessionId, "VALIDATION_GATE", "Verdict: " + verdict);

        return new MarketingPlanCritiqueResult(sessionId, verdict, findingViews, revisedContent);
    }

    private void persistSession(UUID tenantId, MarketingPlanCritiqueRequest request, UUID sessionId,
                                 MarketingPlanContent original, MarketingPlanContent revised, CritiqueVerdict verdict) {
        sessionRepository.save(MarketingPlanCritiqueSession.builder()
                .id(sessionId)
                .tenantId(tenantId)
                .planId(request.planId())
                .verdict(verdict)
                .createdAt(OffsetDateTime.now())
                .build());
        snapshotService.persist(sessionId, PlanSnapshotKind.ORIGINAL, original);
        if (revised != null) {
            snapshotService.persist(sessionId, PlanSnapshotKind.REVISED, revised);
        }
    }

    private List<MarketingPlanCritiqueFindingView> persistFindings(UUID sessionId, List<RoledFinding> findings) {
        OffsetDateTime now = OffsetDateTime.now();
        return findings.stream()
                .map(f -> {
                    CritiqueSeverity severity = MarketingPlanSeverityParser.parse(f.item().severity());
                    MarketingPlanCritiqueFinding saved = findingRepository.save(MarketingPlanCritiqueFinding.builder()
                            .sessionId(sessionId)
                            .role(f.role())
                            .observation(f.item().observation())
                            .risk(f.item().risk())
                            .cause(f.item().cause())
                            .correction(f.item().correction())
                            .severity(severity)
                            .createdAt(now)
                            .build());
                    return new MarketingPlanCritiqueFindingView(saved.getRole(), saved.getObservation(), saved.getRisk(),
                            saved.getCause(), saved.getCorrection(), saved.getSeverity());
                })
                .collect(Collectors.toList());
    }

    private List<MarketingPlanFindingForRevision> toRevisionInput(List<RoledFinding> findings) {
        return findings.stream()
                .map(f -> new MarketingPlanFindingForRevision(f.role().name(), f.item().observation(), f.item().risk(),
                        f.item().cause(), f.item().correction(), f.item().severity()))
                .collect(Collectors.toList());
    }

    private record RoledFinding(MarketingPlanCriticRole role, MarketingPlanCriticFindingItem item) {
    }
}
