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
@Table(name = "creator_profiles")
/**
 * Reusable cast/creator actor profile owned by a user. These rows power the
 * Creator UI cast picker and can be reused across multiple shorts.
 */
public class CreatorProfile {

    /** Primary key for this cast profile. */
    @Id
    private UUID id;

    /** Tenant or organization identifier from auth/platform context. */
    @Column(name = "tenant_id", nullable = false, length = 128)
    private String tenantId;

    /** Creator user identifier from auth context. */
    @Column(name = "user_id", nullable = false, length = 128)
    private String userId;

    /** Optional project where this cast profile was first created. Null means reusable/global. */
    @Column(name = "project_id")
    private UUID projectId;

    /** User-facing actor/cast name shown in the cast picker. */
    @Column(name = "display_name", nullable = false, length = 160)
    private String displayName;

    /** Default role this person usually plays in shorts, such as Main Actor or Friend. */
    @Column(name = "role_in_short", length = 120)
    private String roleInShort;

    /** Flexible actor details: age, gender, vibe, look, profile, style, camera confidence, and future fields. */
    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> attributes = new LinkedHashMap<>();

    /** Whether the user has explicitly saved/confirmed this profile. */
    @Builder.Default
    @Column(nullable = false)
    private boolean confirmed = false;

    /** Timestamp when the cast profile row was created. */
    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    /** Timestamp when the cast profile row was last updated. */
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (attributes == null) {
            attributes = new LinkedHashMap<>();
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
        if (attributes == null) {
            attributes = new LinkedHashMap<>();
        }
        updatedAt = OffsetDateTime.now();
    }
}
