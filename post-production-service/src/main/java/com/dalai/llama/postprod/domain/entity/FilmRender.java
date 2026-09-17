package com.dalai.llama.postprod.domain.entity;

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

/** One assembly of a project into a single film -- see V7. */
@Entity
@Table(name = "film_render")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FilmRender {

    @Id
    @Column(name = "render_id")
    private UUID renderId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private FilmRenderStatus status;

    @Column(name = "shot_count")
    private Integer shotCount;

    /** Comma-separated clip version ids, so a published film can be traced to the exact cut of each
     * shot it contains. Text rather than a join table: it is written once, read by a human when
     * something looks wrong, and never queried by element. */
    @Column(name = "source_version_ids")
    private String sourceVersionIds;

    @Column(name = "width")
    private Integer width;

    @Column(name = "height")
    private Integer height;

    @Column(name = "duration_seconds", precision = 10, scale = 3)
    private BigDecimal durationSeconds;

    @Column(name = "bucket", length = 128)
    private String bucket;

    @Column(name = "object_key", length = 1024)
    private String objectKey;

    /** Whether the client's review page shows this film. Off means invisible, not undownloadable. */
    @Column(name = "published", nullable = false)
    @Builder.Default
    private boolean published = false;

    @Column(name = "published_at")
    private OffsetDateTime publishedAt;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    public boolean isPlayable() {
        return status == FilmRenderStatus.COMPLETED && bucket != null && objectKey != null;
    }
}
