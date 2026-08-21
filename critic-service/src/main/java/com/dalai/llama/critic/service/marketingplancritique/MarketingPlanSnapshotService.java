package com.dalai.llama.critic.service.marketingplancritique;

import com.dalai.llama.critic.domain.PlanSnapshotKind;
import com.dalai.llama.critic.domain.entity.MarketingPlanSnapshot;
import com.dalai.llama.critic.dto.marketingplan.MarketingPlanContent;
import com.dalai.llama.critic.repository.MarketingPlanSnapshotRepository;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * The only place a {@code MarketingPlanContent} is taken apart into relational columns or
 * reassembled from them -- every other class in this package deals in the typed DTO. Marketing-
 * plan sibling of {@code PlanSnapshotService}; simpler since there's no nested character/anchor
 * lists, just eleven flat text sections.
 */
@Service
public class MarketingPlanSnapshotService {

    private final MarketingPlanSnapshotRepository snapshotRepository;

    public MarketingPlanSnapshotService(MarketingPlanSnapshotRepository snapshotRepository) {
        this.snapshotRepository = snapshotRepository;
    }

    public void persist(UUID sessionId, PlanSnapshotKind kind, MarketingPlanContent content) {
        snapshotRepository.save(MarketingPlanSnapshot.builder()
                .id(UUID.randomUUID())
                .sessionId(sessionId)
                .kind(kind)
                .executiveSummary(content.executiveSummary())
                .marketAnalysis(content.marketAnalysis())
                .targetAudienceProfile(content.targetAudienceProfile())
                .positioningStatement(content.positioningStatement())
                .brandStrategy(content.brandStrategy())
                .marketingObjectives(content.marketingObjectives())
                .channelStrategy(content.channelStrategy())
                .contentStrategy(content.contentStrategy())
                .budgetGuidance(content.budgetGuidance())
                .successMetrics(content.successMetrics())
                .risksAndMitigations(content.risksAndMitigations())
                .referencedCaseStudyPatterns(content.referencedCaseStudyPatterns())
                .createdAt(OffsetDateTime.now())
                .build());
    }

    public Optional<MarketingPlanContent> load(UUID sessionId, PlanSnapshotKind kind) {
        return snapshotRepository.findBySessionIdAndKind(sessionId, kind).map(this::toContent);
    }

    private MarketingPlanContent toContent(MarketingPlanSnapshot s) {
        return new MarketingPlanContent(
                s.getExecutiveSummary(),
                s.getMarketAnalysis(),
                s.getTargetAudienceProfile(),
                s.getPositioningStatement(),
                s.getBrandStrategy(),
                s.getMarketingObjectives(),
                s.getChannelStrategy(),
                s.getContentStrategy(),
                s.getBudgetGuidance(),
                s.getSuccessMetrics(),
                s.getRisksAndMitigations(),
                s.getReferencedCaseStudyPatterns());
    }
}
