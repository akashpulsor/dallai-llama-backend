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

/** One tenant, one brand -- this service's own bounded-context rule (a brand can have many
 * {@link ProductProfile}s, each with its own campaign journey). Persisted, editable "marketing
 * and branding team" memory the chat draws on for every session. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "brand_context")
public class BrandContext {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @Column(name = "tenant_id", nullable = false, unique = true)
    private UUID tenantId;

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

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
