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

/** A reference image/detail attachment on a standalone requirement -- the equivalent of {@link
 * ProductReferenceImage} for the entry point that has no product/brand to attach to. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "project_requirement_attachment")
public class ProjectRequirementAttachment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "requirement_id", nullable = false)
    private UUID requirementId;

    @Column(name = "bucket", nullable = false)
    private String bucket;

    @Column(name = "object_key", nullable = false)
    private String objectKey;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
