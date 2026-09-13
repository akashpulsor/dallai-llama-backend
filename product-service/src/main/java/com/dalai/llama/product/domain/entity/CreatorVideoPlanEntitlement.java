package com.dalai.llama.product.domain.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Feature matrix for the creator-video product -- deliberately a separate table from the PBX
 * {@link PlanEntitlement} rather than added columns on it: different product, different concerns,
 * and {@code PlanEntitlement} is already a 60+ column single-table-per-plan matrix that shouldn't
 * grow a second product's unrelated fields onto it.
 */
@Entity
@Table(name = "creator_video_plan_entitlements")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreatorVideoPlanEntitlement {

    @Id
    @Builder.Default
    private UUID id = UUID.randomUUID();

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "plan_id", nullable = false, unique = true)
    private Plan plan;

    @Column(name = "video_creation_enabled", nullable = false)
    @Builder.Default
    private boolean videoCreationEnabled = true;

    @Column(name = "video_download_enabled", nullable = false)
    @Builder.Default
    private boolean videoDownloadEnabled = true;

    @Column(name = "edits_enabled", nullable = false)
    @Builder.Default
    private boolean editsEnabled = false;

    @Column(name = "image_upload_enabled", nullable = false)
    @Builder.Default
    private boolean imageUploadEnabled = false;

    @Column(name = "upscaling_enabled", nullable = false)
    @Builder.Default
    private boolean upscalingEnabled = false;

    @Column(name = "upscale_preview_enabled", nullable = false)
    @Builder.Default
    private boolean upscalePreviewEnabled = false;

    @Column(name = "character_voice_upload_enabled", nullable = false)
    @Builder.Default
    private boolean characterVoiceUploadEnabled = false;

    @Column(name = "brief_url_share_enabled", nullable = false)
    @Builder.Default
    private boolean briefUrlShareEnabled = false;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
        createdAt = updatedAt = Instant.now();
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
