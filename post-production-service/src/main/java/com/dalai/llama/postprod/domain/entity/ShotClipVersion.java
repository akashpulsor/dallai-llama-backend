package com.dalai.llama.postprod.domain.entity;

import com.dalai.llama.postprod.domain.ClipOrigin;
import com.dalai.llama.postprod.domain.ClipVersionStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * One cut of one shot -- see V6 for why a shot's clip is a sequence rather than a file.
 *
 * <p>Read constantly and written rarely, so the read paths are cached in Redis rather than here --
 * see {@code ClipVersionCacheConfig}. Cached at the service layer, not with a Hibernate region,
 * because the thing worth caching is the assembled view for a shot, not individual row loads.
 */
@Entity
@Table(name = "shot_clip_version")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShotClipVersion {

    @Id
    @Column(name = "version_id")
    private UUID versionId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "shot_id", nullable = false)
    private UUID shotId;

    @Column(name = "shot_ref", length = 64)
    private String shotRef;

    /** The video-gen job this cut descends from, so any cut can be traced to the render paid for. */
    @Column(name = "source_job_id")
    private UUID sourceJobId;

    /** Per shot, starting at 1 for the generated clip. What a creator actually refers to. */
    @Column(name = "version_number", nullable = false)
    private int versionNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "origin", nullable = false, length = 32)
    private ClipOrigin origin;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ClipVersionStatus status;

    @Column(name = "bucket", nullable = false, length = 128)
    private String bucket;

    @Column(name = "object_key", nullable = false, length = 1024)
    private String objectKey;

    @Column(name = "duration_seconds", precision = 10, scale = 3)
    private BigDecimal durationSeconds;

    @Column(name = "width")
    private Integer width;

    @Column(name = "height")
    private Integer height;

    @Column(name = "has_audio")
    private Boolean hasAudio;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    /** When this cut became the one the film uses. Null while it is a preview. */
    @Column(name = "accepted_at")
    private OffsetDateTime acceptedAt;
}
