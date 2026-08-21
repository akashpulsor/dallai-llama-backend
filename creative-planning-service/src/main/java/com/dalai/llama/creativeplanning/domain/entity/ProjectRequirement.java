package com.dalai.llama.creativeplanning.domain.entity;

import com.dalai.llama.creativeplanning.domain.BudgetTier;
import com.dalai.llama.creativeplanning.domain.TenantType;
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
 * One flow, two entry points (the design decision this table implements): {@code lockedIdeaId}
 * is set when a brand went through the full campaign-planning chat; it's null for a standalone
 * brief (a solo AI video creator skipping the branding exercise entirely). Either way, the
 * requirement converges here and shares the same review/fund/handoff path.
 * <p>
 * {@code funded} is a state this service records, never a payment this service executes --
 * {@code POST /v1/project-requirements/{id}/mark-funded} is meant to be called by an external
 * payment provider's webhook once a real payment gateway is integrated (not built in this pass;
 * this service never touches money). For COMPANY tenants it's set true at creation time, since
 * their generation cost is already covered by their wallet, not a per-project payment.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "project_requirement")
public class ProjectRequirement {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "tenant_type", nullable = false, length = 16)
    private TenantType tenantType;

    @Column(name = "locked_idea_id")
    private UUID lockedIdeaId;

    @Column(name = "brief_text", nullable = false, columnDefinition = "text")
    private String briefText;

    @Column(name = "target_audience", columnDefinition = "text")
    private String targetAudience;

    @Column(name = "campaign_direction", columnDefinition = "text")
    private String campaignDirection;

    @Enumerated(EnumType.STRING)
    @Column(name = "budget_tier", nullable = false, length = 16)
    private BudgetTier budgetTier;

    @Column(name = "share_token", nullable = false, unique = true, length = 64)
    private String shareToken;

    /** The public link is only live until this instant -- {@code ProjectRequirementService}
     * refuses to resolve an expired token. Refreshing (the client asked to resend because they
     * didn't pay in time) issues a brand-new token and moves this forward, which also invalidates
     * whatever the old link was -- see {@code refreshShareToken}. */
    @Column(name = "share_token_expires_at", nullable = false)
    private OffsetDateTime shareTokenExpiresAt;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "funded", nullable = false)
    private boolean funded;

    @Column(name = "funded_by")
    private UUID fundedBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
