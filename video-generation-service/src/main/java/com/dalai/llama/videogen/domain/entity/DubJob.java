package com.dalai.llama.videogen.domain.entity;

import com.dalai.llama.videogen.domain.JobStatus;
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

import java.time.OffsetDateTime;
import java.util.UUID;

/** One request to record a shot's line -- see V36 for why a dub needs a row at all. */
@Entity
@Table(name = "dub_job")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DubJob {

    @Id
    @Column(name = "job_id")
    private UUID jobId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "shot_id", nullable = false)
    private UUID shotId;

    /** The words being spoken, so a finished take can be matched against the line it was made from
     * -- which is how a take recorded from since-replaced words is spotted. */
    @Column(name = "text")
    private String text;

    /** Reuses {@link JobStatus}: QUEUED while it waits, PROCESSING while it runs, then terminal.
     * The same vocabulary as every other job here, so a page polling two kinds reads one enum. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private JobStatus status;

    @Column(name = "audio_url")
    private String audioUrl;

    @Column(name = "duration_ms")
    private Integer durationMs;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;
}
