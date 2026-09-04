package com.dalai.llama.creativeplanning.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/** One brand can have many products, each with its own campaign journey (its own {@link
 * CampaignPlanningSession}s and reference images). */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "product_profile")
public class ProductProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "brand_context_id", nullable = false)
    private UUID brandContextId;

    /** Set only when this product was created inline while starting a standalone brief (see
     * {@code ProjectRequirementCreationManager}), as opposed to via the full brand/campaign
     * journey ({@code ProductController}) -- same dual-origin shape as {@code LockedIdea}'s
     * {@code sessionId}/{@code projectRequirementId} pair. Null for the latter. */
    @Column(name = "project_requirement_id")
    private UUID projectRequirementId;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Column(name = "category", length = 200)
    private String category;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
