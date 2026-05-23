package com.dalai.llama.creator.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "creator_script_characters")
/**
 * Normalized generated character for a script. A character can later be mapped
 * to a reusable actor profile through creator_character_cast_mappings.
 */
public class CreatorScriptCharacter {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false, length = 128)
    private String tenantId;

    @Column(name = "user_id", nullable = false, length = 128)
    private String userId;

    @Column(name = "script_id", nullable = false)
    private UUID scriptId;

    @Column(name = "locked_idea_id")
    private UUID lockedIdeaId;

    @Column(name = "story_idea_id")
    private UUID storyIdeaId;

    @Column(name = "character_key", nullable = false, length = 160)
    private String characterKey;

    @Column(name = "character_name", nullable = false, length = 160)
    private String characterName;

    @Column(name = "character_role", length = 120)
    private String characterRole;

    @Column(length = 80)
    private String gender;

    @Column(length = 80)
    private String age;

    @Column(name = "age_range", length = 80)
    private String ageRange;

    @Column(columnDefinition = "text")
    private String look;

    @Column(columnDefinition = "text")
    private String profile;

    @Column(columnDefinition = "text")
    private String persona;

    @Column(columnDefinition = "text")
    private String backstory;

    @Column(columnDefinition = "text")
    private String motivation;

    @Column(name = "fear_or_block", columnDefinition = "text")
    private String fearOrBlock;

    @Column(name = "relationship_to_story", columnDefinition = "text")
    private String relationshipToStory;

    @Column(name = "speaking_style", columnDefinition = "text")
    private String speakingStyle;

    @Column(name = "visual_identity", columnDefinition = "text")
    private String visualIdentity;

    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "character_payload", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> characterPayload = new LinkedHashMap<>();

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (characterPayload == null) {
            characterPayload = new LinkedHashMap<>();
        }
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    void preUpdate() {
        if (characterPayload == null) {
            characterPayload = new LinkedHashMap<>();
        }
        updatedAt = OffsetDateTime.now();
    }
}
