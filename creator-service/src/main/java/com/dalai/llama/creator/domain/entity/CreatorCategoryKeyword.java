package com.dalai.llama.creator.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
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

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "creator_category_keywords")
/**
 * Scheduler keyword/query strategy for one category and one no-key trend source.
 */
public class CreatorCategoryKeyword {

    /** Primary key for the category keyword mapping. */
    @Id
    private UUID id;

    /** Category that owns this source keyword mapping. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private CreatorCategory category;

    /** Ingestion source type, for example google_trends, reddit, or youtube_public_pages. */
    @Column(name = "source_type", nullable = false, length = 80)
    private String sourceType;

    /** Locale/country-language context for the terms, for example en-IN. */
    @Column(nullable = false, length = 24)
    private String locale;

    /** Source search terms or community hints to include. */
    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "include_terms", nullable = false, columnDefinition = "jsonb")
    private List<String> includeTerms = new ArrayList<>();

    /** Unsafe, noisy, or irrelevant terms to exclude. */
    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "exclude_terms", nullable = false, columnDefinition = "jsonb")
    private List<String> excludeTerms = new ArrayList<>();

    /** Relative confidence/priority used by the scheduler when collecting or ranking signals. */
    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal weight;

    /** Whether the scheduler should use this mapping. */
    @Column(nullable = false)
    private boolean active;

    /** Timestamp when the keyword mapping was created. */
    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    /** Timestamp when the keyword mapping was last updated. */
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
