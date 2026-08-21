package com.dalai.llama.critic.domain.entity;

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

import java.util.UUID;

/** One row per {@code ShotContext.characters()} entry on a given {@link CritiquePlanSnapshot}. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "critique_plan_character")
public class CritiquePlanCharacter {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @Column(name = "snapshot_id", nullable = false)
    private UUID snapshotId;

    @Column(name = "cast_id")
    private String castId;

    @Column(name = "face_ref_bucket")
    private String faceRefBucket;

    @Column(name = "face_ref_object_key")
    private String faceRefObjectKey;

    @Column(name = "wardrobe_note", columnDefinition = "text")
    private String wardrobeNote;

    @Column(name = "performance_direction", columnDefinition = "text")
    private String performanceDirection;
}
