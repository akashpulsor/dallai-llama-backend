package com.dalai.llama.preprod.domain.entity;

import com.dalai.llama.preprod.domain.ReferenceMediaType;
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

/** A client reference a treatment draws on -- {@code assetId} is the original upload's id in
 * creative-planning-service; the media is never copied. See V73. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "creative_direction_reference")
public class CreativeDirectionReference {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @Column(name = "creative_direction_id", nullable = false)
    private UUID creativeDirectionId;

    @Column(name = "asset_id", nullable = false)
    private UUID assetId;

    @Enumerated(EnumType.STRING)
    @Column(name = "media_type", nullable = false, length = 8)
    private ReferenceMediaType mediaType;

    @Column(name = "bucket")
    private String bucket;

    @Column(name = "object_key", columnDefinition = "text")
    private String objectKey;

    @Column(name = "client_instruction", columnDefinition = "text")
    private String clientInstruction;

    @Column(name = "reference_analysis", columnDefinition = "text")
    private String referenceAnalysis;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
