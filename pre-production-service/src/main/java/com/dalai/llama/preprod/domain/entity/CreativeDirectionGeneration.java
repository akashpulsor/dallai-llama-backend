package com.dalai.llama.preprod.domain.entity;

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

/** One "generate alternatives" round for a project, with the idea and brief exactly as they were
 * sent to the model. See V73. */
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

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
