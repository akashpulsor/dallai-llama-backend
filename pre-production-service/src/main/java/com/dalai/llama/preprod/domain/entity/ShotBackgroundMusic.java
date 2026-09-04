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

/** One current background-music track per shot -- regenerating overwrites the row, same
 * "no version history yet" convention {@link ShotImage} uses. Generated on demand only (a
 * creator's own call, never part of the automatic dispatch pipeline) from the shot's already-
 * planned {@code Shot.soundDesign} ambient-bed description. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "shot_background_music")
public class ShotBackgroundMusic {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "shot_id", nullable = false, unique = true)
    private UUID shotId;

    @Column(name = "bucket", nullable = false)
    private String bucket;

    @Column(name = "object_key", nullable = false)
    private String objectKey;

    /** The prompt actually sent to the music model -- the shot's ambient-bed sound-design
     * description(s), same "kept for debugging/regeneration" reasoning as ShotImage.prompt. */
    @Column(name = "prompt", columnDefinition = "text")
    private String prompt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
