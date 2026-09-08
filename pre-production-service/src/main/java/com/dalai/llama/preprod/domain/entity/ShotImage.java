package com.dalai.llama.preprod.domain.entity;

import com.dalai.llama.preprod.domain.ShotImageKind;
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

/** One current image per (shot, kind) -- regenerating overwrites the row, same "no version
 * history yet" convention {@code StoryboardImageService} used for its single column pair before
 * this replaced it. Superseded {@code Shot.storyboardImageBucket}/{@code storyboardImageObjectKey}
 * (removed): those could only ever hold one image; this table holds all 4 {@link ShotImageKind}s. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "shot_image")
public class ShotImage {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "shot_id", nullable = false)
    private UUID shotId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 16)
    private ShotImageKind kind;

    @Column(name = "bucket", nullable = false)
    private String bucket;

    @Column(name = "object_key", nullable = false)
    private String objectKey;

    /** The full prompt sent to the image model -- kept for debugging/regeneration, same reason
     * {@code sketchPrompt} was already captured on {@code Shot} itself. */
    @Column(name = "prompt", columnDefinition = "text")
    private String prompt;

    /** The CastProfile face/product reference this render was conditioned on, if any -- null for
     * a plain text-to-image render (e.g. LIGHTING/CAMERA_PLAN, or a PRODUCTION shot with no
     * resolved cast assignment yet). */
    @Column(name = "reference_cast_profile_id")
    private UUID referenceCastProfileId;

    /** {@link com.dalai.llama.preprod.service.ShotImageDescriptionService}'s vision-model caption
     * of this image, cached so re-locking a project doesn't re-pay for the same LLM call. Null
     * until the image has been described at least once. */
    @Column(name = "description", columnDefinition = "text")
    private String description;

    /** Any rendered on-image text the vision model detected in this specific image (packaging
     * copy, signage, on-frame captions, motion-graphic text) -- populated by the same
     * {@link com.dalai.llama.preprod.service.ShotImageDescriptionService} call that fills
     * {@code description}, using the on-screen-text field the V50 prompt was already asking for
     * but was previously discarded on read. "Has rendered text" is derived: this field non-null
     * and non-empty. See V56 migration. */
    @Column(name = "on_screen_text", columnDefinition = "text")
    private String onScreenText;

    /** BCP-47 code of {@link #onScreenText} when set (e.g. {@code en}, {@code hi}, {@code
     * hi-Latn} for romanized Hindi/Hinglish). Null when {@code onScreenText} is null. */
    @Column(name = "on_screen_text_language", length = 16)
    private String onScreenTextLanguage;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
