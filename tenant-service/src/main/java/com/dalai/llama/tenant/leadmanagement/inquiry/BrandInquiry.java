package com.dalai.llama.tenant.leadmanagement.inquiry;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/** A signed-in brand asking a creator for a video (V32). Converting it creates a brief in
 * creative-planning, after which the existing brief, payment and review flow takes over. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "lead_brand_inquiry")
public class BrandInquiry {

    public enum Status { NEW, VIEWED, CONVERTED, DECLINED }

    /** CREATOR: sent from a creator's page. LANDING: sent to Dalaillama and routed to a creator. */
    public enum Source { CREATOR, LANDING }

    public enum BudgetBand { UNDER_50K, FROM_50K_TO_2L, FROM_2L_TO_8L, OVER_8L, NOT_SURE }

    public enum Timeline { ASAP, THIS_MONTH, THIS_QUARTER, EXPLORING }

    @Id
    @Column(name = "id")
    private UUID id;

    /** The creator it is for; null while a request sent to Dalaillama waits to be routed. */
    @Column(name = "tenant_id")
    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 16)
    @Builder.Default
    private Source source = Source.CREATOR;

    /** The brand's industry for this video (the router's match); null when not given. */
    @Enumerated(EnumType.STRING)
    @Column(name = "industry", length = 32)
    private com.dalai.llama.tenant.showcase.domain.ShowcaseIndustry industry;

    @Column(name = "brand_contact_id", nullable = false)
    private UUID brandContactId;

    /** The film the brand was watching when they asked, if any. */
    @Column(name = "showcase_item_id")
    private UUID showcaseItemId;

    /** The tracked mail link they arrived through, if any (Phase C attribution). */
    @Column(name = "attribution_token", length = 32)
    private String attributionToken;

    @Enumerated(EnumType.STRING)
    @Column(name = "budget_band", length = 16)
    private BudgetBand budgetBand;

    @Enumerated(EnumType.STRING)
    @Column(name = "timeline", length = 16)
    private Timeline timeline;

    @Column(name = "message", nullable = false, length = 1000)
    private String message;

    @Column(name = "product_name", length = 160)
    private String productName;

    @Column(name = "product_description", length = 1000)
    private String productDescription;

    @Column(name = "target_audience", length = 500)
    private String targetAudience;

    @Column(name = "campaign_direction", length = 1000)
    private String campaignDirection;

    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    /* Comma-separated, e.g. "English,Hindi". */
    @Column(name = "languages", length = 200)
    private String languages;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status;

    @Column(name = "requirement_id")
    private UUID requirementId;

    @Column(name = "brief_share_token", length = 100)
    private String briefShareToken;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
