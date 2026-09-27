package com.dalai.llama.preprod.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The persisted form of a project's score plan (see V69).
 *
 * <p>Named {@code MusicPlanRecord} rather than {@code MusicPlan} because the DTO of that name is
 * the thing the rest of the code passes around; this is only its storage. The structured plan is
 * kept as JSON in {@code planJson} -- it is read and written whole, never queried into, so a
 * column-per-field schema would buy nothing and would need a migration every time the planner
 * learns a new field.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "music_plan")
public class MusicPlanRecord {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "total_duration_seconds", nullable = false)
    private BigDecimal totalDurationSeconds;

    @Column(name = "plan_json", nullable = false, columnDefinition = "text")
    private String planJson;

    /** Editable by the creator. Always re-composable from {@code planJson}. */
    @Column(name = "master_prompt", nullable = false, columnDefinition = "text")
    private String masterPrompt;

    @Column(name = "ending_strategy", columnDefinition = "text")
    private String endingStrategy;

    /** Null until the score has been generated. */
    @Column(name = "bucket", length = 120)
    private String bucket;

    @Column(name = "object_key", length = 512)
    private String objectKey;

    @Column(name = "generated_model_id", length = 160)
    private String generatedModelId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
