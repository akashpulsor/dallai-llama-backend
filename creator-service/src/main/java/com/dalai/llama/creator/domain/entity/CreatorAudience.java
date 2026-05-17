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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "creator_audiences")
/**
 * Suggested or confirmed target audience for a creator project. AI suggestions,
 * manual edits, and final audience decisions all live here as reusable memory.
 */
public class CreatorAudience {

    /** Primary key for this audience record. */
    @Id
    private UUID id;

    /** Tenant or organization identifier from auth/platform context. */
    @Column(name = "tenant_id", nullable = false, length = 128)
    private String tenantId;

    /** Creator user identifier from auth context. */
    @Column(name = "user_id", nullable = false, length = 128)
    private String userId;

    /** Optional creator project this audience belongs to. */
    @Column(name = "project_id")
    private UUID projectId;

    /** Human-readable segment title shown in the Creator UI. */
    @Column(nullable = false, length = 200)
    private String title;

    /** Age, gender, geography, language, and other demographic targeting details. */
    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> demographics = new LinkedHashMap<>();

    /** Interests and affinities that make the short relevant to this audience. */
    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<String> interests = new ArrayList<>();

    /** Motivations, pains, content psychology, prompt inputs, and AI reasoning context. */
    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> psychographics = new LinkedHashMap<>();

    /** Preferred tone or format, for example Funny & Honest or Educational. */
    @Column(name = "content_preference", length = 240)
    private String contentPreference;

    /** True when the row was produced by AI instead of directly typed by the user. */
    @Builder.Default
    @Column(name = "ai_suggested", nullable = false)
    private boolean aiSuggested = false;

    /** True after the user has accepted this audience for the creative workflow. */
    @Builder.Default
    @Column(nullable = false)
    private boolean confirmed = false;

    /** Timestamp when the audience row was created. */
    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    /** Timestamp when the audience row was last updated. */
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (demographics == null) {
            demographics = new LinkedHashMap<>();
        }
        if (interests == null) {
            interests = new ArrayList<>();
        }
        if (psychographics == null) {
            psychographics = new LinkedHashMap<>();
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
        if (demographics == null) {
            demographics = new LinkedHashMap<>();
        }
        if (interests == null) {
            interests = new ArrayList<>();
        }
        if (psychographics == null) {
            psychographics = new LinkedHashMap<>();
        }
        updatedAt = OffsetDateTime.now();
    }
}
