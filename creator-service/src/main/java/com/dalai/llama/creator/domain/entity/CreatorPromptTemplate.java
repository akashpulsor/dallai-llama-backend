package com.dalai.llama.creator.domain.entity;

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

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "creator_prompt_templates")
/**
 * Versioned prompt template used to render reproducible Creator AI prompts.
 */
public class CreatorPromptTemplate {

    /** Primary key for the prompt template. */
    @Id
    private UUID id;

    /** Stable prompt type key, for example TREND_PREDICT. */
    @Column(name = "template_key", nullable = false, length = 80)
    private String templateKey;

    /** Template version used for reproducibility. */
    @Column(nullable = false)
    private Integer version;

    /** Human-readable template name. */
    @Column(nullable = false, length = 200)
    private String name;

    /** Prompt template text with placeholders. */
    @Column(name = "template_body", nullable = false, columnDefinition = "text")
    private String templateBody;

    /** Template lifecycle state such as ACTIVE or RETIRED. */
    @Column(nullable = false, length = 32)
    private String status;

    /** Timestamp when the template was created. */
    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    /** Timestamp when the template was last updated. */
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
