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

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/** One dialogue timestamp within a shot -- the second a line starts and how long it runs. A shot
 * can have more than one (multiple lines "cloned in parts"). Drives video-generation-service's
 * beat-matched auto-dub: with beats present, Seedance's native audio is turned off and this
 * timing is what the synthesized cloned-voice track gets built against instead. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "shot_dialogue_beat")
public class ShotDialogueBeat {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "shot_id", nullable = false)
    private UUID shotId;

    @Column(name = "order_index", nullable = false)
    private Integer orderIndex;

    @Column(name = "start_seconds", nullable = false)
    private BigDecimal startSeconds;

    @Column(name = "duration_seconds", nullable = false)
    private BigDecimal durationSeconds;

    @Column(name = "text", nullable = false, columnDefinition = "text")
    private String text;

    /** Null defaults to the shot's own {@code primaryCharacterKey} at resolution time. */
    @Column(name = "character_key", length = 160)
    private String characterKey;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
