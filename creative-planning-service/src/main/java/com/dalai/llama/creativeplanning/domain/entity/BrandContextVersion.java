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

/** One snapshot of a tenant's brand context -- see {@code V14__brand_context_version.sql}'s
 * javadoc for why this is a separate, additive table rather than versioning {@link BrandContext}
 * itself the way {@code Screenplay} is versioned: every other service that resolves a tenant's
 * brand keeps joining on the single live {@code brand_context} row, unchanged. {@link
 * com.dalai.llama.creativeplanning.service.BrandContextService#upsert} keeps this in sync. No
 * source/parentId -- unlike a script or screenplay, nothing here is ever LLM-generated. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "brand_context_version")
public class BrandContextVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "brand_context_id", nullable = false)
    private UUID brandContextId;

    @Column(name = "version", nullable = false)
    private Integer version;

    @Column(name = "brand_name", nullable = false, length = 200)
    private String brandName;

    @Column(name = "industry", length = 200)
    private String industry;

    @Column(name = "brand_voice", columnDefinition = "text")
    private String brandVoice;

    @Column(name = "target_audience", columnDefinition = "text")
    private String targetAudience;

    @Column(name = "brand_values", columnDefinition = "text")
    private String brandValues;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
