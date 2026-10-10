package com.dalai.llama.tenant.showcase.domain.entity;

import com.dalai.llama.tenant.showcase.domain.CreatorLevel;
import com.dalai.llama.tenant.showcase.domain.ProfileStatus;
import com.dalai.llama.tenant.showcase.domain.ShowcaseIndustry;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
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
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/** A creator's public profile, served at {@code dalaillama.in/c/<handle>}. Created by
 * {@link com.dalai.llama.tenant.onboarding.CreatorOnboardingService} on subscription.activated
 * (V26 migration). The handle is the only creator identifier allowed in public payloads. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "creator_public_profile")
public class CreatorPublicProfile {

    @Id
    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(name = "handle", nullable = false, unique = true, length = 40)
    private String handle;

    /** When the creator last changed their handle; null if they never did. Drives the
     * change cooldown. */
    @Column(name = "handle_changed_at")
    private OffsetDateTime handleChangedAt;

    @Column(name = "display_name", nullable = false, length = 80)
    private String displayName;

    @Column(name = "headline", length = 120)
    private String headline;

    @Column(name = "bio", length = 1000)
    private String bio;

    @Column(name = "avatar_url", length = 512)
    private String avatarUrl;

    @Column(name = "country_code", length = 2)
    private String countryCode;

    @Column(name = "website_url", length = 255)
    private String websiteUrl;

    @Builder.Default
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "creator_profile_industry", joinColumns = @JoinColumn(name = "tenant_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "industry", length = 32)
    private Set<ShowcaseIndustry> industries = EnumSet.noneOf(ShowcaseIndustry.class);

    /** Creator's opt-out from automatic picks (Phase D). */
    @Builder.Default
    @Column(name = "auto_picks_enabled", nullable = false)
    private boolean autoPicksEnabled = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ProfileStatus status;

    /** Signed-in brands following this creator; kept in step by FollowService. */
    @Column(name = "follower_count", nullable = false)
    private int followerCount;

    /** Written by the ranking run (rule 11); a cache of that run's output, never set by hand. */
    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "level", nullable = false, length = 4)
    private CreatorLevel level = CreatorLevel.L0;

    /** Level weight times the reinforcement decay (rule 13), from the last ranking run. */
    @Column(name = "standing_factor", precision = 5, scale = 4)
    private BigDecimal standingFactor;

    /** When the creator's newest verified-funded platform film was locked, from the last run. */
    @Column(name = "last_funded_platform_at")
    private OffsetDateTime lastFundedPlatformAt;

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
