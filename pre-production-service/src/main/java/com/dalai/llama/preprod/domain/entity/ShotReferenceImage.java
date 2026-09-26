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

/** One uploaded reference image for a shot flagged as needs-multi-image. Rows in creator-set
 * ordinal order feed both the storyboard tile display and the video-generation prompt (the
 * prompt walks the images with their captions so the model knows what each image represents).
 * Cascaded delete with the parent shot -- if the shot is removed, its references go too. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "shot_reference_image")
public class ShotReferenceImage {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "shot_id", nullable = false)
    private UUID shotId;

    @Column(name = "bucket", nullable = false, length = 120)
    private String bucket;

    @Column(name = "object_key", nullable = false, length = 512)
    private String objectKey;

    @Column(name = "content_type", length = 120)
    private String contentType;

    /** Optional short caption the creator gives -- feeds the video-generation prompt so the
     * model knows what to make of this specific image ("empty state", "success screen"). */
    @Column(name = "caption", length = 240)
    private String caption;

    @Column(name = "ordinal", nullable = false)
    @Builder.Default
    private Integer ordinal = 0;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
