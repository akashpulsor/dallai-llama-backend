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

/** One per project -- deterministically recomputed (never a fresh LLM call, mirrors creator-
 * service's real Java-computed videoConsistencyBible) whenever the shot list regenerates. Its
 * {@link ContinuityLock} rows are what {@code ShotContextCommonFields.continuityAnchors} turns
 * into every shot's GLOBAL_CAMPAIGN anchors. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "continuity_bible")
public class ContinuityBible {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "project_id", nullable = false, unique = true)
    private UUID projectId;

    @Column(name = "negative_prompt", columnDefinition = "text")
    private String negativePrompt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
