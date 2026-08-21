package com.dalai.llama.creativeplanning.domain.entity;

import com.dalai.llama.creativeplanning.domain.BudgetTier;
import com.dalai.llama.creativeplanning.domain.MarketingPlanStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A full strategic marketing/branding plan for a brand (optionally scoped to one product) --
 * distinct from {@link LockedIdea}, which is one locked campaign concept. This is the broader
 * document: market analysis, positioning, channel strategy, budget guidance, the works. Every
 * section is a typed column, not a jsonb blob, matching this service's own no-maps rule.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "marketing_plan")
public class MarketingPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "brand_context_id", nullable = false)
    private UUID brandContextId;

    @Column(name = "product_profile_id")
    private UUID productProfileId;

    @Column(name = "target_audience_input", columnDefinition = "text")
    private String targetAudienceInput;

    @Enumerated(EnumType.STRING)
    @Column(name = "budget_tier", nullable = false, length = 16)
    private BudgetTier budgetTier;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private MarketingPlanStatus status;

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

    /** Named real-world frameworks/case studies the model grounded this plan in -- transparency
     * into its reasoning, not a lookup against an actual case-study database (this service has
     * no such corpus; it's leaning on the model's own training knowledge, framed honestly). */
    @Column(name = "referenced_case_study_patterns", columnDefinition = "text")
    private String referencedCaseStudyPatterns;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
