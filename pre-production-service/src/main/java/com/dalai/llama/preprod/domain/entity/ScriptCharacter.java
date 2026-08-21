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

/** Narrative identity only -- casting a real face onto this happens in {@link CastAssignment},
 * same split as creator-service's real creator_script_characters / creator_character_cast_mappings
 * pair, which this deliberately mirrors. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "script_character")
public class ScriptCharacter {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "script_id", nullable = false)
    private UUID scriptId;

    @Column(name = "character_key", nullable = false, length = 160)
    private String characterKey;

    @Column(name = "character_name", nullable = false, length = 160)
    private String characterName;

    @Column(name = "character_role", length = 120)
    private String characterRole;

    @Column(name = "description")
    private String description;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
