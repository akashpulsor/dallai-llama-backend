package com.dalai.llama.postprod.domain.entity;

import com.dalai.llama.postprod.domain.SoundLayerKind;
import com.dalai.llama.postprod.domain.SoundLayerSource;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/** A piece of music or a sound effect placed on the film's timeline, anchored to a shot. See V14. */
@Entity
@Table(name = "sound_layer")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SoundLayer {

    @Id
    @Column(name = "layer_id")
    private UUID layerId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    /** The shot it is anchored to; it moves with the shot when shots are reordered. */
    @Column(name = "shot_id", nullable = false)
    private UUID shotId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 16)
    private SoundLayerKind kind;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 16)
    private SoundLayerSource source;

    /** What it was generated from; null for an upload. */
    @Column(name = "prompt")
    private String prompt;

    @Column(name = "bucket", nullable = false)
    private String bucket;

    @Column(name = "object_key", nullable = false)
    private String objectKey;

    @Column(name = "duration_seconds")
    private BigDecimal durationSeconds;

    /** Where it starts, from the start of its shot. May run past the shot's end. */
    @Column(name = "offset_ms", nullable = false)
    private int offsetMs;

    @Column(name = "volume_db", nullable = false)
    private BigDecimal volumeDb;

    @Column(name = "fade_in_ms", nullable = false)
    private int fadeInMs;

    @Column(name = "fade_out_ms", nullable = false)
    private int fadeOutMs;

    /** In the film or not. Off keeps it, so it can be switched back on without regenerating. */
    @Column(name = "included", nullable = false)
    private boolean included;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
