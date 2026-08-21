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

/** An uploaded reference image (the "Pinterest-image flow") -- {@link ReferenceImageAnalysis}
 * holds the vision analysis computed from it. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "product_reference_image")
public class ProductReferenceImage {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "product_profile_id", nullable = false)
    private UUID productProfileId;

    @Column(name = "bucket", nullable = false)
    private String bucket;

    @Column(name = "object_key", nullable = false)
    private String objectKey;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
