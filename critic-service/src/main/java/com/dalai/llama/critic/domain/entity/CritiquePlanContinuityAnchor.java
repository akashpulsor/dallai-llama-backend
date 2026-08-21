package com.dalai.llama.critic.domain.entity;

import com.dalai.llama.critic.domain.AnchorType;
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

import java.util.UUID;

/** One row per {@code ShotContext.continuityAnchors()} entry on a given {@link CritiquePlanSnapshot}. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "critique_plan_continuity_anchor")
public class CritiquePlanContinuityAnchor {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @Column(name = "snapshot_id", nullable = false)
    private UUID snapshotId;

    @Enumerated(EnumType.STRING)
    @Column(name = "anchor_type", length = 24)
    private AnchorType anchorType;

    @Column(name = "subject_id")
    private String subjectId;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Column(name = "reference_object_key")
    private String referenceObjectKey;
}
