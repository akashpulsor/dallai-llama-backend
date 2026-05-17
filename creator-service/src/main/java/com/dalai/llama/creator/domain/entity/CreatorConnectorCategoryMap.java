package com.dalai.llama.creator.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
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
@Table(name = "creator_connector_category_map")
/**
 * Mapping of connectors to categories they can collect signals for.
 */
public class CreatorConnectorCategoryMap {

    /** Composite key made of connector id and category id. */
    @EmbeddedId
    private CreatorConnectorCategoryMapId id;

    /** Connector allowed to collect for the category. */
    @MapsId("connectorId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "connector_id", nullable = false)
    private CreatorSourceConnector connector;

    /** Category the connector can collect for. */
    @MapsId("categoryId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private CreatorCategory category;

    /** Whether this connector/category mapping is active. */
    @Column(nullable = false)
    private boolean enabled;

    /** Execution order for this connector within the category. */
    @Column(nullable = false)
    private Integer priority;

    /** Relative source weight for ranking/classification. */
    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal weight;

    /** Timestamp when the mapping was created. */
    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    /** Timestamp when the mapping was last updated. */
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
