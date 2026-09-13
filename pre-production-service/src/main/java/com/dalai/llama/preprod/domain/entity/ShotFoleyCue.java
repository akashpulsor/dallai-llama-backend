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

/** One foley cue within a shot -- a sound, and the millisecond it happens at. Derived once when
 * the shot is planned and carried in the prepare bundle, so video-generation-service reads the
 * cue sheet instead of paying to re-derive it on every prepare. Sits alongside the shot's other
 * plan rows (camera plan, lighting plan, dialogue beats). */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "shot_foley_cue")
public class ShotFoleyCue {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "shot_id", nullable = false)
    private UUID shotId;

    @Column(name = "timestamp_ms", nullable = false)
    private Integer timestampMs;

    /** Free text rather than an enum: video-generation-service owns the CueType taxonomy this
     * maps onto, and a value it does not recognise degrades there rather than failing a write
     * here. */
    @Column(name = "cue_type", nullable = false, length = 32)
    private String cueType;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
