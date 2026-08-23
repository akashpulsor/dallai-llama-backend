package com.dalai.llama.preprod.domain.entity;

import com.dalai.llama.preprod.domain.ProductReferenceClassification;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/** One confirmed product/subject reference photo per shot -- what the PRODUCTION image kind
 * conditions on in addition to (or instead of) the shot's assigned CastProfile. Analyze (vision
 * call, not persisted) then confirm (this row, upsert per shot) mirrors creator-service's real
 * two-step product-reference flow. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "shot_product_reference")
public class ShotProductReference {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "shot_id", nullable = false, unique = true)
    private UUID shotId;

    @Enumerated(EnumType.STRING)
    @Column(name = "classification", nullable = false, length = 16)
    private ProductReferenceClassification classification;

    @Column(name = "bucket", nullable = false)
    private String bucket;

    @Column(name = "object_key", nullable = false)
    private String objectKey;

    /** CAST only -- vision-described appearance of the person/product in the photo. */
    @Column(name = "person_description", columnDefinition = "text")
    private String personDescription;

    /** INSPIRATION only -- what the vision analysis detected in the photo. */
    @Column(name = "detected_subject", columnDefinition = "text")
    private String detectedSubject;

    @Column(name = "dominant_mood", length = 160)
    private String dominantMood;

    @Column(name = "reference_camera_angle", length = 160)
    private String referenceCameraAngle;

    @Column(name = "reference_lighting_style", length = 160)
    private String referenceLightingStyle;

    @Column(name = "reference_motion", length = 160)
    private String referenceMotion;

    /** INSPIRATION only -- true tells generation to ignore the photographed subject entirely and
     * borrow only abstract color/lighting/composition. */
    @Column(name = "ignore_subject", nullable = false)
    @Builder.Default
    private Boolean ignoreSubject = false;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
