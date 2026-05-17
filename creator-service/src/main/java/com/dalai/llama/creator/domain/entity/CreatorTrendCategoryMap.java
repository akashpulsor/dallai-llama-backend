package com.dalai.llama.creator.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "creator_trend_category_map")
/**
 * Many-to-many classification record allowing one trend to belong to multiple categories.
 */
public class CreatorTrendCategoryMap {

    /** Composite key made of trend id and category id. */
    @EmbeddedId
    private CreatorTrendCategoryMapId id;

    /** Classifier confidence for this category assignment. */
    @Column(name = "confidence_score", nullable = false, precision = 5, scale = 2)
    private BigDecimal confidenceScore;

    /** Mapping origin such as scheduler, ai, admin, or manual. */
    @Column(name = "mapped_by", nullable = false, length = 48)
    private String mappedBy;

    /** Timestamp when the category mapping was created. */
    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
