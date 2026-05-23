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
@Table(name = "creator_script_beats")
/**
 * Normalized story beat for a generated creator script.
 */
public class CreatorScriptBeat {

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

    @Column(name = "beat_number", nullable = false)
    private Integer beatNumber;

    @Column(nullable = false, length = 220)
    private String title;

    @Column(columnDefinition = "text")
    private String summary;

    @Column(name = "character_focus", length = 240)
    private String characterFocus;

    @Column(name = "emotional_purpose", columnDefinition = "text")
    private String emotionalPurpose;

    @Column(name = "estimated_seconds")
    private Integer estimatedSeconds;

    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "beat_payload", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> beatPayload = new LinkedHashMap<>();

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
        if (beatPayload == null) {
            beatPayload = new LinkedHashMap<>();
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
        if (beatPayload == null) {
            beatPayload = new LinkedHashMap<>();
        }
        updatedAt = OffsetDateTime.now();
    }
}
