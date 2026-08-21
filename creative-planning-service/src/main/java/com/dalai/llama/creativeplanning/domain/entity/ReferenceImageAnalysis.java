package com.dalai.llama.creativeplanning.domain.entity;

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

/** Vision analysis of one {@link ProductReferenceImage}, typed fields (not a jsonb blob) --
 * closes the doc's "detailed analysis of a Pinterest reference image feeding a product frame"
 * flow. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "reference_image_analysis")
public class ReferenceImageAnalysis {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "reference_image_id", nullable = false, unique = true)
    private UUID referenceImageId;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Column(name = "dominant_colors", columnDefinition = "text")
    private String dominantColors;

    @Column(name = "style_notes", columnDefinition = "text")
    private String styleNotes;

    @Column(name = "subject_matter", columnDefinition = "text")
    private String subjectMatter;

    @Column(name = "suggested_use_case", columnDefinition = "text")
    private String suggestedUseCase;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
