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

/** The explicit assign/snapshot join between a narrative {@link ScriptCharacter} and a
 * reusable {@link CastProfile} within one project -- mirrors creator-service's real
 * creator_character_cast_mappings. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "cast_assignment")
public class CastAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "script_character_id", nullable = false)
    private UUID scriptCharacterId;

    @Column(name = "cast_profile_id", nullable = false)
    private UUID castProfileId;

    @Column(name = "wardrobe_note")
    private String wardrobeNote;

    @Column(name = "performance_direction")
    private String performanceDirection;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
