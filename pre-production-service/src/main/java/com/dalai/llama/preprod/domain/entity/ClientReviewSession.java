package com.dalai.llama.preprod.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * A TRANSACTIONAL client review: the client opens one review, batches several changes into it
 * (product-frame changes, story changes, ...) through the review chat, then closes it -- at which
 * point the batched changes are applied to the storyboard together. The number of STARTED reviews
 * (not chat messages) counts against {@link Project#getReviewAllowance()}; a review started beyond
 * that allowance is {@link #paid} (unlocked by an extra-review payment).
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "client_review_session")
public class ClientReviewSession {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    /** OPEN while the client is actively reviewing; CLOSED once ended. */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    /** SATISFIED (apply the batched changes) or CHANGES_REQUESTED, set when the review is closed. */
    @Column(name = "outcome", length = 24)
    private String outcome;

    /** True when this review was unlocked by an extra-review payment (started beyond the allowance). */
    @Column(name = "paid", nullable = false)
    private boolean paid;

    @Column(name = "started_at", nullable = false)
    private OffsetDateTime startedAt;

    @Column(name = "ended_at")
    private OffsetDateTime endedAt;
}
