package com.dalai.llama.tenant.showcase.domain.entity;

import com.dalai.llama.tenant.showcase.domain.OfficialUploadStatus;
import com.dalai.llama.tenant.showcase.domain.ShowcaseFormat;
import com.dalai.llama.tenant.showcase.domain.ShowcaseIndustry;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/** One film queued for upload to Dalaillama's own YouTube channel (V29). */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "official_upload_job")
public class OfficialUploadJob {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "project_id", nullable = false, unique = true)
    private UUID projectId;

    @Enumerated(EnumType.STRING)
    @Column(name = "industry", nullable = false, length = 32)
    private ShowcaseIndustry industry;

    @Enumerated(EnumType.STRING)
    @Column(name = "format", nullable = false, length = 32)
    private ShowcaseFormat format;

    @Column(name = "client_label", length = 80)
    private String clientLabel;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private OfficialUploadStatus status;

    @Column(name = "youtube_video_id", length = 16)
    private String youtubeVideoId;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "error", length = 500)
    private String error;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
