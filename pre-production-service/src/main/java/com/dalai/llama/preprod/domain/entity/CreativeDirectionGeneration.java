package com.dalai.llama.preprod.domain.entity;

import com.dalai.llama.joblifecycle.JobLifecycleStatus;
import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

/** One "generate alternatives" round for a project, with the idea and brief exactly as they were
 * sent to the model -- and, since V74, the asynchronous job that writes its treatments. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "creative_direction_generation")
public class CreativeDirectionGeneration {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "round", nullable = false)
    private int round;

    @Column(name = "locked_idea_id")
    private UUID lockedIdeaId;

    @Column(name = "idea_title", columnDefinition = "text")
    private String ideaTitle;

    @Column(name = "idea_concept", columnDefinition = "text")
    private String ideaConcept;

    @Column(name = "idea_target_audience", columnDefinition = "text")
    private String ideaTargetAudience;

    @Column(name = "idea_campaign_angle", columnDefinition = "text")
    private String ideaCampaignAngle;

    @Column(name = "idea_key_message", columnDefinition = "text")
    private String ideaKeyMessage;

    @Column(name = "idea_tone", columnDefinition = "text")
    private String ideaTone;

    @Column(name = "brief_text", columnDefinition = "text")
    private String briefText;

    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    @Column(name = "created_by")
    private UUID createdBy;

    /** How many treatments this round asked for (5-10; 3 for rounds written before V74). */
    @Column(name = "requested_count", nullable = false)
    private int requestedCount;

    /** The round is the job: PENDING until its treatments are stored, then COMPLETED or FAILED. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private JobLifecycleStatus status;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "llm_idempotency_key")
    private String llmIdempotencyKey;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
