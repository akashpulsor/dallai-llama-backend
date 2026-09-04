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

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One row per shot-export-PDF the creator ran. Persisted right after the compressed PDF lands
 * in MinIO so history is durable and downloadable across sessions (the signed URL a caller
 * carries around expires in an hour; the row's {@code bucket + object_key} always re-signs).
 * Not a job in the workflow sense -- the export happens synchronously in one request; the row
 * is a receipt, not an in-progress task.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "shot_export_job")
public class ShotExportJob {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    /** May be null for exports triggered by an internal service call that has no user context.
     * The creator-triggered {@code POST /v1/projects/{id}/export-pdf} always carries a userId. */
    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "bucket", nullable = false, length = 128)
    private String bucket;

    @Column(name = "object_key", nullable = false, length = 1024)
    private String objectKey;

    @Column(name = "shot_count", nullable = false)
    private int shotCount;

    /** Size of the final PDF in bytes -- purely informational (history UI: "1.2MB"), never
     * checked against a quota. Null for a row saved before the size was known (shouldn't happen
     * in the current flow but the column is nullable so a future write path stays flexible). */
    @Column(name = "file_size_bytes")
    private Long fileSizeBytes;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
