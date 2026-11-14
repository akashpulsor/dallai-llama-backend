package com.dalai.llama.postprod.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/** A creator's uploaded film waiting for its Dalaillama-stamped copy -- see V20. */
@Entity
@Table(name = "upload_stamp")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UploadStamp {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "source_bucket", nullable = false, length = 63)
    private String sourceBucket;

    @Column(name = "source_key", nullable = false, length = 512)
    private String sourceKey;

    @Column(name = "width")
    private Integer width;

    /** NULL = due; then PENDING, DONE or FAILED, as on film_render. */
    @Column(name = "status", length = 16)
    private String status;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "requested_at")
    private OffsetDateTime requestedAt;

    @Column(name = "stamped_object_key", length = 512)
    private String stampedObjectKey;

    @Column(name = "error", length = 500)
    private String error;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }
}
