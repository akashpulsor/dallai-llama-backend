package com.dalai.llama.creator.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.Mutability;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "creator_prompt_runs")
/**
 * Audit and memory record for one rendered AI prompt and provider response.
 */
public class CreatorPromptRun {

    /** Primary key for the prompt run. */
    @Id
    private UUID id;

    /** Tenant or organization identifier from auth/platform context. */
    @Column(name = "tenant_id", nullable = false, length = 128)
    private String tenantId;

    /** Creator user identifier from auth context. */
    @Column(name = "user_id", nullable = false, length = 128)
    private String userId;

    /** Project associated with this prompt run, when available. */
    @Column(name = "project_id")
    private UUID projectId;

    /** Generation job associated with this prompt run, when available. */
    @Column(name = "job_id")
    private UUID jobId;

    /** Template row used to render the prompt. */
    @Column(name = "prompt_template_id")
    private UUID promptTemplateId;

    /** Prompt template key copied for audit stability. */
    @Column(name = "prompt_template_key", nullable = false, length = 80)
    private String promptTemplateKey;

    /** Prompt template version copied for audit stability. */
    @Column(name = "prompt_template_version", nullable = false)
    private Integer promptTemplateVersion;

    /** Full rendered prompt sent to the AI provider. */
    @Column(name = "rendered_prompt", nullable = false, columnDefinition = "text")
    private String renderedPrompt;

    /** JSON project memory/input snapshot used to render the prompt. */
    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Mutability(ReplacementOnlyJsonMutabilityPlan.class)
    @Column(name = "input_snapshot", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> inputSnapshot = new LinkedHashMap<>();

    /** AI provider name such as mock, openai, or gemini. */
    @Column(nullable = false, length = 80)
    private String provider;

    /** Provider model identifier. */
    @Column(nullable = false, length = 120)
    private String model;

    /** Structured AI response or parsed output. */
    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Mutability(ReplacementOnlyJsonMutabilityPlan.class)
    @Column(name = "output_payload", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> outputPayload = new LinkedHashMap<>();

    /** Prompt run status such as PENDING, COMPLETED, or FAILED. */
    @Column(nullable = false, length = 32)
    private String status;

    /** Failure reason when status is FAILED. */
    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    /** Token usage metadata for future real AI providers. */
    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Mutability(ReplacementOnlyJsonMutabilityPlan.class)
    @Column(name = "token_metadata", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> tokenMetadata = new LinkedHashMap<>();

    /** Cost metadata for future billing/reconciliation. */
    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Mutability(ReplacementOnlyJsonMutabilityPlan.class)
    @Column(name = "cost_metadata", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> costMetadata = new LinkedHashMap<>();

    /** Timestamp when the prompt run was created. */
    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    /** Timestamp when the prompt run completed or failed. */
    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }
}
