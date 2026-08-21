package com.dalai.llama.critic.domain.entity;

import com.dalai.llama.critic.domain.PlanSnapshotKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/** A full snapshot of a marketing plan's eleven sections, each its own typed column -- the
 * marketing-plan sibling of {@link CritiquePlanSnapshot}, reusing the same {@link
 * PlanSnapshotKind} (ORIGINAL/REVISED) vocabulary. No jsonb, same rule as the shot snapshot. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "marketing_plan_snapshot")
public class MarketingPlanSnapshot {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 8)
    private PlanSnapshotKind kind;

    @Column(name = "executive_summary", columnDefinition = "text")
    private String executiveSummary;

    @Column(name = "market_analysis", columnDefinition = "text")
    private String marketAnalysis;

    @Column(name = "target_audience_profile", columnDefinition = "text")
    private String targetAudienceProfile;

    @Column(name = "positioning_statement", columnDefinition = "text")
    private String positioningStatement;

    @Column(name = "brand_strategy", columnDefinition = "text")
    private String brandStrategy;

    @Column(name = "marketing_objectives", columnDefinition = "text")
    private String marketingObjectives;

    @Column(name = "channel_strategy", columnDefinition = "text")
    private String channelStrategy;

    @Column(name = "content_strategy", columnDefinition = "text")
    private String contentStrategy;

    @Column(name = "budget_guidance", columnDefinition = "text")
    private String budgetGuidance;

    @Column(name = "success_metrics", columnDefinition = "text")
    private String successMetrics;

    @Column(name = "risks_and_mitigations", columnDefinition = "text")
    private String risksAndMitigations;

    @Column(name = "referenced_case_study_patterns", columnDefinition = "text")
    private String referencedCaseStudyPatterns;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
