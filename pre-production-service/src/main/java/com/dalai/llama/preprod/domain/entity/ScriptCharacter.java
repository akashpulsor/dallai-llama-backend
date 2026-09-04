package com.dalai.llama.preprod.domain.entity;

import com.dalai.llama.preprod.domain.CharacterType;
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

/** Narrative identity only -- casting a real face onto this happens in {@link CastAssignment},
 * same split as creator-service's real creator_script_characters / creator_character_cast_mappings
 * pair, which this deliberately mirrors. {@link CharacterType} distinguishes a human performer
 * from a product the ad exists to showcase -- script generation sets it per character; either kind
 * is cast the same way, via a {@link CastAssignment} to a matching-typed {@link CastProfile}. */
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

    @Enumerated(EnumType.STRING)
    @Column(name = "character_type", nullable = false, length = 16)
    private CharacterType characterType;

    // --- Casting-relevant character detail, restoring creator-service's real
    // creator_script_characters typed columns (never jsonb there either -- a clean 1:1 port, not
    // a redesign). HUMAN characters only; left null for PRODUCT-typed entries. ---

    @Column(name = "gender", length = 40)
    private String gender;

    @Column(name = "age")
    private Integer age;

    @Column(name = "age_range", length = 40)
    private String ageRange;

    @Column(name = "look", columnDefinition = "text")
    private String look;

    @Column(name = "complexion", length = 80)
    private String complexion;

    @Column(name = "profile", columnDefinition = "text")
    private String profile;

    @Column(name = "persona", columnDefinition = "text")
    private String persona;

    @Column(name = "backstory", columnDefinition = "text")
    private String backstory;

    @Column(name = "motivation", columnDefinition = "text")
    private String motivation;

    @Column(name = "fear_or_block", columnDefinition = "text")
    private String fearOrBlock;

    @Column(name = "relationship_to_story", columnDefinition = "text")
    private String relationshipToStory;

    @Column(name = "speaking_style", columnDefinition = "text")
    private String speakingStyle;

    @Column(name = "visual_identity", columnDefinition = "text")
    private String visualIdentity;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
