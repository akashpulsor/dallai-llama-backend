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

/** "What the client has in mind" -- a mood/style reference image attached directly to a {@link
 * com.dalai.llama.creativeplanning.domain.entity.ProjectRequirement}, distinct from {@link
 * ProductReferenceImage} (an image of the actual product). {@link ProjectReferenceImageAnalysis}
 * holds the vision analysis computed from it, deferred until the requirement is funded -- see
 * {@code ReferenceMaterialAnalysisService}. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "project_reference_image")
public class ProjectReferenceImage {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "project_requirement_id", nullable = false)
    private UUID projectRequirementId;

    @Column(name = "bucket", nullable = false)
    private String bucket;

    @Column(name = "object_key", nullable = false)
    private String objectKey;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
