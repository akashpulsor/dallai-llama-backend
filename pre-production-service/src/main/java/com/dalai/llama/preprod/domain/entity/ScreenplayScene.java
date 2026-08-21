package com.dalai.llama.preprod.domain.entity;

import com.dalai.llama.preprod.domain.TimeOfDay;
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

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "screenplay_scene")
public class ScreenplayScene {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "screenplay_id", nullable = false)
    private UUID screenplayId;

    @Column(name = "scene_number", nullable = false)
    private Integer sceneNumber;

    @Column(name = "slug", nullable = false, length = 220)
    private String slug;

    @Column(name = "location")
    private String location;

    @Enumerated(EnumType.STRING)
    @Column(name = "time_of_day", length = 16)
    private TimeOfDay timeOfDay;

    @Column(name = "summary")
    private String summary;

    /** Restores creator-service's real creator_script_beats fields at scene granularity (this
     * build collapses beat and scene into one level, see the service's class comment). */
    @Column(name = "character_focus", length = 240)
    private String characterFocus;

    @Column(name = "emotional_purpose", columnDefinition = "text")
    private String emotionalPurpose;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
