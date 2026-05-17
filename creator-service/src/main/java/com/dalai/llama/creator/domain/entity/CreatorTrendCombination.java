package com.dalai.llama.creator.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
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
@Table(name = "creator_trend_combinations")
/**
 * Valid target platform and category pair that can be processed by prediction/scheduler flows.
 */
public class CreatorTrendCombination {

    /** Primary key for the platform/category combination. */
    @Id
    private UUID id;

    /** Target publishing platform code, for example instagram_reels. */
    @Column(name = "platform_code", nullable = false, length = 64)
    private String platformCode;

    /** Topic category code, for example fitness. */
    @Column(name = "category_code", nullable = false, length = 64)
    private String categoryCode;

    /** Whether users and schedulers can use this platform/category pair. */
    @Column(nullable = false)
    private boolean enabled;

    /** Timestamp when the combination was created. */
    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    /** Timestamp when the combination was last updated. */
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
