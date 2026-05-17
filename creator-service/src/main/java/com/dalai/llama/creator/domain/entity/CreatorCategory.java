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

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "creator_categories")
/**
 * Master row for Creator trend categories shown as UI filters and used by ingestion/prompt logic.
 */
public class CreatorCategory {

    /** Primary key for the category master record. */
    @Id
    private UUID id;

    /** Stable machine category code used by APIs, jobs, prompts, and filters. */
    @Column(nullable = false, unique = true, length = 64)
    private String code;

    /** Human-readable category label shown in UI. */
    @Column(name = "display_name", nullable = false, length = 160)
    private String displayName;

    /** Short category description for UI/admin display. */
    @Column(columnDefinition = "text")
    private String description;

    /** Frontend icon key, usually mapped to a lucide icon. */
    @Column(name = "icon_key", length = 80)
    private String iconKey;

    /** Display ordering for category pickers. */
    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;

    /** Whether the category can be used by scheduler/API logic. */
    @Column(nullable = false)
    private boolean active;

    /** Whether the category appears in end-user UI. */
    @Column(nullable = false)
    private boolean visible;

    /** Prompt guidance specific to this category. */
    @Column(name = "prompt_context", columnDefinition = "text")
    private String promptContext;

    /** Timestamp when the category record was created. */
    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    /** Timestamp when the category record was last updated. */
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
