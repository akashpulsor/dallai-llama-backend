package com.dalai.llama.videogen.domain.entity;

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
 * One clip a shot used to have.
 *
 * <p>Written before a repair moves the job's pointer, never after -- a version recorded afterwards
 * is a version that was already lost. See V35 for why: the job holds exactly one pointer to its
 * clip, so every repair made the clip it replaced unreachable, and a repair that produced something
 * unplayable took the shot's only video with it.
 *
 * <p>Holds the bucket and key rather than a URL. A presigned URL expires within the hour, so a
 * version stored as one would be a record of something that used to be fetchable.
 */
@Entity
@Table(name = "video_gen_job_output_version")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VideoGenJobOutputVersion {

    @Id
    @Column(name = "version_id")
    private UUID versionId;

    @Column(name = "job_id", nullable = false)
    private UUID jobId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "bucket", nullable = false, length = 128)
    private String bucket;

    @Column(name = "object_key", nullable = false, length = 512)
    private String objectKey;

    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    /** GENERATED / DUBBED / SILENCED / TAIL_FROZEN / TAIL_GENERATED / UPLOADED -- the same
     * vocabulary as {@code VideoGenJob.outputOrigin}, since a value moves between the two unchanged. */
    @Column(name = "origin", nullable = false, length = 32)
    private String origin;

    /** When this stopped being the shot's clip. */
    @Column(name = "superseded_at", nullable = false)
    private OffsetDateTime supersededAt;

    @Column(name = "created_by")
    private UUID createdBy;
}
