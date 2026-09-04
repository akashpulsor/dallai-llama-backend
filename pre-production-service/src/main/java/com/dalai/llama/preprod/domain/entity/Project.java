package com.dalai.llama.preprod.domain.entity;

import com.dalai.llama.preprod.domain.BudgetTier;
import com.dalai.llama.preprod.domain.ProjectStatus;
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
 * Root aggregate of pre-production-service, always created from a locked idea handed off by
 * creative-planning-service ({@link #lockedIdeaId} is an external reference, never a local FK --
 * that service is out of this module's bounded context).
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "project")
public class Project {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "locked_idea_id", nullable = false)
    private UUID lockedIdeaId;

    @Enumerated(EnumType.STRING)
    @Column(name = "budget_tier", nullable = false, length = 16)
    private BudgetTier budgetTier;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private ProjectStatus status;

    /** How many client review rounds are included before the paywall. Set (optionally) on the
     * new-project tab; defaults to 2. A transactional review beyond this count requires payment. */
    @Column(name = "review_allowance", nullable = false)
    private int reviewAllowance;

    /** Creator's on/off switch for client reviews. When false, the client cannot open a new review
     * (paid or free) -- the creator has closed reviews for this project. Defaults to true. */
    @Column(name = "reviews_enabled", nullable = false)
    private boolean reviewsEnabled;

    /** Creator's manual gate for the client's ability to download the assembled final video from
     * the public review page. False by default; the creator flips this to true once the client
     * has paid them (outside the platform, for now). The client can always PREVIEW the video --
     * this gates the download link only. When real payment flow lands later, a successful
     * verify can auto-flip this same column. */
    @Column(name = "final_video_download_unlocked", nullable = false)
    private boolean finalVideoDownloadUnlocked;

    /** Unguessable token for the client's public review page ({@code GET /v1/public/projects/
     * {token}}) -- same "possession of the token is the authorization" convention creative-
     * planning-service's ProjectRequirement.shareToken already uses. Generated lazily the first
     * time the public page is resolved, not at project creation. */
    @Column(name = "client_review_token", unique = true, length = 64)
    private String clientReviewToken;

    /** chat-service session id, created once the client locks the package -- null until then. */
    @Column(name = "chat_session_id")
    private UUID chatSessionId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
