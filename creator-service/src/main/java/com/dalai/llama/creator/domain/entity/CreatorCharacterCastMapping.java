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
@Table(name = "creator_character_cast_mappings")
/**
 * Assignment layer that maps generated story characters to saved cast profiles.
 * This preserves who should play each scripted character before storyboard work.
 */
public class CreatorCharacterCastMapping {

    /** Primary key for this character-to-cast assignment. */
    @Id
    private UUID id;

    /** Tenant or organization identifier from auth/platform context. */
    @Column(name = "tenant_id", nullable = false, length = 128)
    private String tenantId;

    /** Creator user identifier from auth context. */
    @Column(name = "user_id", nullable = false, length = 128)
    private String userId;

    /** Optional active creator project. */
    @Column(name = "project_id")
    private UUID projectId;

    /** Locked brief that owns this production workflow. */
    @Column(name = "locked_idea_id", nullable = false)
    private UUID lockedIdeaId;

    /** Saved story idea/script whose characters are being cast. */
    @Column(name = "story_idea_id", nullable = false)
    private UUID storyIdeaId;

    /** Optional generated screenplay id when the mapping is revised after screenplay generation. */
    @Column(name = "script_id")
    private UUID scriptId;

    /** Optional normalized script character row that this mapping assigns to an actor. */
    @Column(name = "script_character_id")
    private UUID scriptCharacterId;

    /** Stable frontend/backend key for the character, normally slug/name plus index. */
    @Column(name = "character_key", nullable = false, length = 160)
    private String characterKey;

    /** Human-readable generated character name. */
    @Column(name = "character_name", nullable = false, length = 160)
    private String characterName;

    /** Generated story role, such as Main creator or Reaction character. */
    @Column(name = "character_role", length = 120)
    private String characterRole;

    /** Saved cast profile selected to perform this character. */
    @Column(name = "cast_profile_id")
    private UUID castProfileId;

    /** Snapshot of selected cast name for quick UI rendering and history. */
    @Column(name = "cast_display_name", length = 160)
    private String castDisplayName;

    /** Snapshot of generated character data at assignment time. */
    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "character_payload", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> characterPayload = new LinkedHashMap<>();

    /** Snapshot of selected cast profile data at assignment time. */
    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "cast_payload", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> castPayload = new LinkedHashMap<>();

    /** Timestamp when this mapping was created. */
    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    /** Timestamp when this mapping was last updated. */
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
        if (castPayload == null) {
            castPayload = new LinkedHashMap<>();
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
        if (castPayload == null) {
            castPayload = new LinkedHashMap<>();
        }
        updatedAt = OffsetDateTime.now();
    }
}
