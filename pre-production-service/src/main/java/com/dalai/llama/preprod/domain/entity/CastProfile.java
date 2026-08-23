package com.dalai.llama.preprod.domain.entity;

import com.dalai.llama.preprod.domain.CastProfileType;
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

/** {@code projectId == null} is a reusable library entry, same convention as creator-service's
 * real {@code creator_profiles.project_id}. Doubles as both a real actor to cast (name/age/gender,
 * face image, voice sample) and a product the ad showcases (name/description, product image) --
 * see {@link CastProfileType}; {@code faceRefBucket}/{@code faceRefObjectKey} is the product image
 * for a PRODUCT profile, same columns, same downstream resolution path. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "cast_profile")
public class CastProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "project_id")
    private UUID projectId;

    @Enumerated(EnumType.STRING)
    @Column(name = "profile_type", nullable = false, length = 16)
    private CastProfileType profileType;

    @Column(name = "display_name", nullable = false, length = 160)
    private String displayName;

    @Column(name = "face_ref_bucket", nullable = false)
    private String faceRefBucket;

    @Column(name = "face_ref_object_key", nullable = false)
    private String faceRefObjectKey;

    @Column(name = "description")
    private String description;

    /** ACTOR profiles only -- null on PRODUCT profiles. */
    @Column(name = "age")
    private Integer age;

    /** ACTOR profiles only -- free text (not an enum: gender identity isn't a fixed small set). */
    @Column(name = "gender", length = 32)
    private String gender;

    /** Raw voice sample to clone for dialogue, ACTOR profiles only. Both null until the creator
     * uploads one -- cloning itself (turning this into a usable voice model) is a video-generation
     * concern downstream, not this service's job. */
    @Column(name = "voice_ref_bucket")
    private String voiceRefBucket;

    @Column(name = "voice_ref_object_key")
    private String voiceRefObjectKey;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
