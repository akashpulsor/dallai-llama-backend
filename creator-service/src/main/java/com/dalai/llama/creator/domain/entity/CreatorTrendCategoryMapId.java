package com.dalai.llama.creator.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
@Embeddable
/**
 * Composite key for the many-to-many trend/category classification table.
 */
public class CreatorTrendCategoryMapId implements Serializable {

    /** Trend being mapped to a category. */
    @Column(name = "trend_id", nullable = false)
    private UUID trendId;

    /** Category assigned to the trend. */
    @Column(name = "category_id", nullable = false)
    private UUID categoryId;
}
