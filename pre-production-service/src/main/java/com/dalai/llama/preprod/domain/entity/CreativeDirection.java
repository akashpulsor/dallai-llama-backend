package com.dalai.llama.preprod.domain.entity;

import com.dalai.llama.preprod.domain.CreativeDirectionReviewStatus;
import com.dalai.llama.preprod.domain.ReviewActor;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

/** One director's treatment for a project's locked idea. A revision is a new row; an APPROVED row
 * is never edited. See V73 and {@link CreativeDirectionReviewStatus}. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "creative_direction")
public class CreativeDirection {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "generation_id", nullable = false)
    private UUID generationId;

    /** Position within its generation; 1 is the AI's recommendation. Kept across revisions. */
    @Column(name = "option_number", nullable = false)
    private int optionNumber;

    @Column(name = "version", nullable = false)
    private int version;

    @Column(name = "revised_from_id")
    private UUID revisedFromId;

    @Column(name = "title", nullable = false, columnDefinition = "text")
    private String title;

    @Column(name = "creative_concept", nullable = false, columnDefinition = "text")
    private String creativeConcept;

    @Column(name = "directors_treatment", nullable = false, columnDefinition = "text")
    private String directorsTreatment;

    @Column(name = "storytelling_style", columnDefinition = "text")
    private String storytellingStyle;

    @Column(name = "story_period", columnDefinition = "text")
    private String storyPeriod;

    @Column(name = "color_treatment", columnDefinition = "text")
    private String colorTreatment;

    @Column(name = "contrast", columnDefinition = "text")
    private String contrast;

    @Column(name = "texture", columnDefinition = "text")
    private String texture;

    @Column(name = "overall_aesthetic", columnDefinition = "text")
    private String overallAesthetic;

    @Column(name = "cinematography_philosophy", columnDefinition = "text")
    private String cinematographyPhilosophy;

    @Column(name = "emotional_journey", columnDefinition = "text")
    private String emotionalJourney;

    @Column(name = "sound_direction", columnDefinition = "text")
    private String soundDirection;

    @Column(name = "signature_creative_device", columnDefinition = "text")
    private String signatureCreativeDevice;

    @Column(name = "creative_rationale", columnDefinition = "text")
    private String creativeRationale;

    /** The AI's advisory pick within its generation -- never implies approval. */
    @Column(name = "recommended", nullable = false)
    private boolean recommended;

    @Column(name = "recommendation_reason", columnDefinition = "text")
    private String recommendationReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "review_status", nullable = false, length = 24)
    private CreativeDirectionReviewStatus reviewStatus;

    @Column(name = "approved_at")
    private OffsetDateTime approvedAt;

    @Column(name = "approved_by")
    private UUID approvedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "approved_via", length = 16)
    private ReviewActor approvedVia;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
