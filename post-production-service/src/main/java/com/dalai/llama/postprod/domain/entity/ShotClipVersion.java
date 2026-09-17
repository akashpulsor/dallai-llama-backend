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
    /** When this cut became the one the film uses. Null while it is a preview. */
    @Column(name = "accepted_at")
    private OffsetDateTime acceptedAt;

    /** When someone took this cut away to edit it. Null means it is not out.
     *
     * <p>Only the coming-back half used to leave a trace, so "what am I still waiting on" had no
     * answer and a shot taken away on Friday looked identical to one nobody had touched. */
    @Column(name = "downloaded_for_edit_at")
    private OffsetDateTime downloadedForEditAt;

    @Column(name = "downloaded_for_edit_by")
    private UUID downloadedForEditBy;

    /** The cut this one was edited from, so a version that went out and came back is a chain rather
     * than two unrelated rows. Null for anything that was not an upload. */
    @Column(name = "edited_from_version_id")
    private UUID editedFromVersionId;

    /** Whether the client may watch this shot on its own, ahead of any film. Off means invisible to
     * them, not merely undownloadable -- the same rule the film uses. */
    @Column(name = "published", nullable = false)
    @Builder.Default
    private boolean published = false;

    @Column(name = "published_at")
    private OffsetDateTime publishedAt;

    /**
     * Whether this cut has been turned down.
     *
     * <p>A flag rather than a {@link com.dalai.llama.postprod.domain.ClipVersionStatus} value on
     * purpose: status says where a cut stands relative to the film, and rejecting says nothing about
     * that. The ACTIVE cut is untouched by a rejection, and nothing here is deleted -- the row stays
     * watchable so the rejection can be taken back and so a creator can still see what they
     * declined.
     */
    @Column(name = "rejected", nullable = false)
    @Builder.Default
    private boolean rejected = false;

    @Column(name = "rejected_at")
    private OffsetDateTime rejectedAt;
}
