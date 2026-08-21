package com.dalai.llama.videogen.domain.entity;

import com.dalai.llama.videogen.domain.FlagState;
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

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "shot_prompt")
public class ShotPrompt {

    @Id
    @Column(name = "prompt_id")
    private UUID promptId;

    @Column(name = "job_id", nullable = false)
    private UUID jobId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "parent_prompt_id")
    private UUID parentPromptId;

    @Column(name = "variant_label")
    private String variantLabel;

    @Column(name = "prompt_original", nullable = false)
    private String promptOriginal;

    @Column(name = "prompt_compressed")
    private String promptCompressed;

    @Column(name = "compression_applied", nullable = false)
    private Boolean compressionApplied;

    @Column(name = "original_length")
    private Integer originalLength;

    @Column(name = "compressed_length")
    private Integer compressedLength;

    @Column(name = "named_entities_validated")
    private Boolean namedEntitiesValidated;

    @Column(name = "negative_prompt", nullable = false)
    private String negativePrompt;

    @Enumerated(EnumType.STRING)
    @Column(name = "dialogue_flag", nullable = false, length = 8)
    private FlagState dialogueFlag;

    @Enumerated(EnumType.STRING)
    @Column(name = "captions_flag", nullable = false, length = 8)
    private FlagState captionsFlag;

    @Column(nullable = false)
    private Boolean shipped;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
