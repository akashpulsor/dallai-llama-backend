package com.dalai.llama.tenant.showcase.domain.entity;

import com.dalai.llama.tenant.showcase.domain.ShowcaseFormat;
import com.dalai.llama.tenant.showcase.domain.ShowcaseIndustry;
import com.dalai.llama.tenant.showcase.domain.ShowcaseItemStatus;
import com.dalai.llama.tenant.showcase.domain.ShowcaseOrigin;
import com.dalai.llama.tenant.showcase.domain.PlatformProof;
import com.dalai.llama.tenant.showcase.domain.VideoChannel;
import com.dalai.llama.tenant.showcase.domain.VideoHostType;
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

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/** One video on a creator's profile (V28). Points at a cached YouTube video; {@link #publicId}
 * is the only id that may appear in a public URL. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "showcase_item")
public class ShowcaseItem {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "public_id", nullable = false, unique = true, length = 12)
    private String publicId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "youtube_video_id", nullable = false, unique = true, length = 16)
    private String youtubeVideoId;

    @Enumerated(EnumType.STRING)
    @Column(name = "origin", nullable = false, length = 10)
    private ShowcaseOrigin origin;

    /** PLATFORM items only; never returned publicly. */
    @Column(name = "source_project_id", unique = true)
    private UUID sourceProjectId;

    @Enumerated(EnumType.STRING)
    @Column(name = "platform_proof", length = 16)
    private PlatformProof platformProof;

    /** Whose channel holds a PLATFORM film; null for EXTERNAL (always the creator's). */
    @Enumerated(EnumType.STRING)
    @Column(name = "channel", length = 10)
    private VideoChannel channel;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "host", nullable = false, length = 8)
    private VideoHostType host = VideoHostType.YOUTUBE;

    /** When the client locked the project (paid in full); raw fact copied from pre-production. */
    @Column(name = "client_locked_at")
    private OffsetDateTime clientLockedAt;

    /** When the project passed the evidence gate; null means not verified as funded. */
    @Column(name = "funded_verified_at")
    private OffsetDateTime fundedVerifiedAt;

    /** When the client accepted marketing use; without it the film never goes into brand mail. */
    @Column(name = "marketing_consent_at")
    private OffsetDateTime marketingConsentAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "industry", nullable = false, length = 32)
    private ShowcaseIndustry industry;

    @Enumerated(EnumType.STRING)
    @Column(name = "format", nullable = false, length = 32)
    private ShowcaseFormat format;

    /** How the client is described publicly, e.g. "D2C skincare brand"; the client's name stays
     * hidden by default. */
    @Column(name = "client_label", length = 80)
    private String clientLabel;

    /** Replaces the YouTube title on our pages when set. */
    @Column(name = "title_override", length = 120)
    private String titleOverride;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ShowcaseItemStatus status;

    @Column(name = "hidden_by_ops", nullable = false)
    private boolean hiddenByOps;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "play_count", nullable = false)
    private int playCount;

    @Column(name = "full_play_count", nullable = false)
    private int fullPlayCount;

    /** Likes on our own pages (Phase B). */
    @Column(name = "like_count", nullable = false)
    private int likeCount;

    /** Distinct brand reports (rule 18). */
    @Column(name = "report_count", nullable = false)
    private int reportCount;

    /** Verified brand requests that started from this item (Phase B). */
    @Column(name = "inquiry_count", nullable = false)
    private int inquiryCount;

    /** Written by the ranking run; null when the item isn't publicly showable. */
    @Column(name = "global_score", precision = 8, scale = 6)
    private BigDecimal globalScore;

    @Column(name = "score_version", length = 40)
    private String scoreVersion;

    /** Guaranteed landing placement until then (rule 14: immediate reinforcement for a newly
     * published verified-funded film). */
    @Column(name = "spotlight_until")
    private OffsetDateTime spotlightUntil;

    /** When the creator confirmed they may show this video (the client agreed, or it is theirs). */
    @Column(name = "rights_confirmed_at", nullable = false)
    private OffsetDateTime rightsConfirmedAt;

    @Column(name = "published_at", nullable = false)
    private OffsetDateTime publishedAt;

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
