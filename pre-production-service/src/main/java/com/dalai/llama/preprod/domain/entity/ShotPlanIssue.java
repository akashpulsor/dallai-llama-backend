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

/** Deterministic (plain Java, no LLM) quality findings against the shot list -- restores
 * creator-service's real ShotPlanCriticService.checkCoverageCompleteness/checkShotTypeVariety.
 * Its actor-consistency and wardrobe-continuity checks are NOT ported: this service assigns cast
 * once per character project-wide (no per-shot override exists), so those two failure modes are
 * structurally impossible here, not merely unchecked. Recomputed wholesale whenever the shot list
 * regenerates, same as {@link ContinuityBible}. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "shot_plan_issue")
public class ShotPlanIssue {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "shot_ref", nullable = false, length = 64)
    private String shotRef;

    @Column(name = "category", nullable = false, length = 40)
    private String category;

    @Column(name = "message", nullable = false, columnDefinition = "text")
    private String message;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
