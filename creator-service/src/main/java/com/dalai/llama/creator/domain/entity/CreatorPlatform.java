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
@Table(name = "creator_platforms")
/**
 * Master row for Creator platforms.
 *
 * Target platforms are shown to creators as publishing destinations. Signal sources are hidden
 * ingestion sources used by the scheduler.
 */
public class CreatorPlatform {

    /** Primary key for the platform master record. */
    @Id
    private UUID id;

    /** Stable machine code used by APIs, jobs, prompts, and UI selections. */
    @Column(nullable = false, unique = true, length = 64)
    private String code;

    /** Human-readable platform label shown in UI. */
    @Column(name = "display_name", nullable = false, length = 160)
    private String displayName;

    /** Short product description for UI/admin display. */
    @Column(columnDefinition = "text")
    private String description;

    /** Frontend icon key, usually mapped to a lucide icon. */
    @Column(name = "icon_key", length = 80)
    private String iconKey;

    /** Display ordering for platform pickers. */
    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;

    /** Whether the platform can be used by scheduler/API logic. */
    @Column(nullable = false)
    private boolean active;

    /** Whether the platform appears in end-user UI. */
    @Column(nullable = false)
    private boolean visible;

    /** True when creators can optimize content for this publishing platform. */
    @Column(name = "target_platform", nullable = false)
    private boolean targetPlatform;

    /** True when this row represents a trend data source rather than a publishing destination. */
    @Column(name = "signal_source", nullable = false)
    private boolean signalSource;

    /** True when the platform is short-form vertical video focused. */
    @Column(name = "short_form", nullable = false)
    private boolean shortForm;

    /** Prompt guidance specific to this platform. */
    @Column(name = "prompt_context", columnDefinition = "text")
    private String promptContext;

    /** Timestamp when the platform record was created. */
    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    /** Timestamp when the platform record was last updated. */
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
