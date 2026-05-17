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
@Table(name = "creator_ideas")
/**
 * Creator concept memory. A row can be a manual/original idea, AI-generated idea,
 * trend-derived idea, or the locked brief that later drives storyboard generation.
 */
public class CreatorIdea {

    /** Primary key for the creator idea. */
    @Id
    private UUID id;

    /** Tenant or organization identifier from auth/platform context. */
    @Column(name = "tenant_id", nullable = false, length = 128)
    private String tenantId;

    /** Creator user identifier from auth context. */
    @Column(name = "user_id", nullable = false, length = 128)
    private String userId;

    /** Optional project this idea belongs to. */
    @Column(name = "project_id")
    private UUID projectId;

    /** Optional trend used to derive this idea. Null for original/user-written ideas. */
    @Column(name = "trend_id")
    private UUID trendId;

    /** Idea source such as TREND, ORIGINAL, AI_GENERATED, or CAST_ENHANCED. */
    @Column(nullable = false, length = 48)
    private String source;

    /** Short user-facing idea title. */
    @Column(nullable = false, length = 240)
    private String title;

    /** Brief concept summary or creator-provided one sentence idea. */
    @Column(columnDefinition = "text")
    private String summary;

    /** Optional full script once generation expands the brief. */
    @Column(columnDefinition = "text")
    private String script;

    /** Optional generated scene outline before a full storyboard exists. */
    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<Map<String, Object>> scenes = new ArrayList<>();

    /** Intended short duration in seconds, normally 30, 45, or 60. */
    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    /** Lifecycle state such as DRAFT, LOCKED, GENERATED, SAVED, or ARCHIVED. */
    @Column(nullable = false, length = 32)
    private String status;

    /** Whether the creator saved this idea for later reuse. */
    @Builder.Default
    @Column(nullable = false)
    private boolean saved = false;

    /** Job that generated or expanded this idea, when applicable. */
    @Column(name = "generation_job_id")
    private UUID generationJobId;

    /** Prompt run that generated or expanded this idea, when applicable. */
    @Column(name = "prompt_run_id")
    private UUID promptRunId;

    /** Selection memory used to preserve what the user explicitly chose in UI. */
    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "selection_context", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> selectionContext = new LinkedHashMap<>();

    /** Timestamp when this idea was explicitly locked as the active production brief. */
    @Column(name = "locked_at")
    private OffsetDateTime lockedAt;

    /** Timestamp when the idea row was created. */
    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    /** Timestamp when the idea row was last updated. */
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (source == null || source.isBlank()) {
            source = "ORIGINAL";
        }
        if (status == null || status.isBlank()) {
            status = "DRAFT";
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
        updatedAt = OffsetDateTime.now();
    }
}
