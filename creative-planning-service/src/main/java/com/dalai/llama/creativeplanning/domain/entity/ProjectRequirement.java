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

import java.math.BigDecimal;
import java.math.RoundingMode;
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
 * <p>
 * {@code durationSeconds}/{@code languages}/{@code quoted*} are only ever set on the standalone
 * path (entry point B) -- {@code budgetTier} is still populated there too, but only as an
 * internal compatibility value auto-derived from {@code durationSeconds} (see {@code
 * ProjectRequirementService#deriveLegacyBudgetTier}) for the sake of downstream consumers
 * ({@code ProjectRequirementIdeaService}'s prompt context, pre-production-service's own {@code
 * Project.budgetTier}) that still key off the old LEAN/STANDARD/PREMIUM tiers -- it is never
 * shown to a user, who only ever sees duration, languages, and the quoted price.
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

    /** Which of the tenant's (possibly several) brands this brief is for -- nullable, a brief can
     * have no brand at all. Stamped at creation time: entry point B (standalone) from whichever
     * brand the creator picked or created inline; entry point A (from a locked idea) from that
     * idea's campaign session, which already carries its own brandContextId. */
    @Column(name = "brand_context_id")
    private UUID brandContextId;

    @Column(name = "brief_text", nullable = false, columnDefinition = "text")
    private String briefText;

    @Column(name = "target_audience", columnDefinition = "text")
    private String targetAudience;

    @Column(name = "campaign_direction", columnDefinition = "text")
    private String campaignDirection;

    @Enumerated(EnumType.STRING)
    @Column(name = "budget_tier", nullable = false, length = 16)
    private BudgetTier budgetTier;

    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    /** Comma-joined language names, e.g. {@code "English,Hindi"} -- see {@code
     * ProjectRequirementService} for the join/split helpers. Flat text column, matching this
     * entity's other free-text fields, rather than a separate child table. */
    @Column(name = "languages", columnDefinition = "text")
    private String languages;

    @Column(name = "quoted_platform_cost", precision = 12, scale = 2)
    private BigDecimal quotedPlatformCost;

    @Column(name = "quoted_creator_margin_percent", precision = 5, scale = 2)
    private BigDecimal quotedCreatorMarginPercent;

    @Column(name = "quoted_total_price", precision = 12, scale = 2)
    private BigDecimal quotedTotalPrice;

    @Column(name = "quoted_currency", length = 3)
    private String quotedCurrency;

    /** What percentage of {@code quotedTotalPrice} the client must actually pay to unlock the
     * project -- default 100 (full payment). A creator may lower this to accept a partial/"token"
     * payment upfront; the remainder is a business matter collected outside the app, never a
     * second charge this service tracks or triggers. See {@link #getRequiredAmount()}. */
    @Column(name = "required_payment_percent", nullable = false)
    @Builder.Default
    private int requiredPaymentPercent = 100;

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

    /** Set whenever the client edits this brief through the public share link (see {@code
     * ProjectRequirementService#updateFromClient}) -- a lightweight "client updated this brief"
     * signal for the creator's requirement list, short of a full notification system. */
    @Column(name = "client_updated_at")
    private OffsetDateTime clientUpdatedAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    /** What the client actually has to pay right now to unlock the project -- {@code
     * quotedTotalPrice} scaled by {@code requiredPaymentPercent}, rounded up so a fractional
     * currency unit is never left uncollected. Null if this requirement has no quote yet (entry
     * point A). */
    public BigDecimal getRequiredAmount() {
        if (quotedTotalPrice == null) {
            return null;
        }
        return quotedTotalPrice
                .multiply(BigDecimal.valueOf(requiredPaymentPercent))
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.CEILING);
    }
}
