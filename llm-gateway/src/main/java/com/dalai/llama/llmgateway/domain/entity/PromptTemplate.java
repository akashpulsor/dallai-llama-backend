package com.dalai.llama.llmgateway.domain.entity;

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

/**
 * System prompts as data, not string literals in caller services. Append-only: never UPDATE
 * {@code content} -- publish a new version and flip {@code active} (same discipline as
 * {@link RateCard}). Exactly one active row per {@code taskKey}, enforced by a partial unique
 * index (see V10 migration).
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "prompt_template")
public class PromptTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "template_id")
    private Long templateId;

    @Column(name = "task_key", nullable = false, length = 64)
    private String taskKey;

    @Column(nullable = false)
    private Integer version;

    @Column(nullable = false)
    private String content;

    @Column(nullable = false)
    private Boolean active;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;
}
