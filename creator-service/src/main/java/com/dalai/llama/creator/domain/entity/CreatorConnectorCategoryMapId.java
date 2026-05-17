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
 * Composite key for connector/category scheduler eligibility.
 */
public class CreatorConnectorCategoryMapId implements Serializable {

    /** Connector allowed to collect for the category. */
    @Column(name = "connector_id", nullable = false)
    private UUID connectorId;

    /** Category the connector can collect for. */
    @Column(name = "category_id", nullable = false)
    private UUID categoryId;
}
