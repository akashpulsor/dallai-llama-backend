package com.dalai.llama.creativeplanning.domain.entity;

import com.dalai.llama.creativeplanning.domain.BudgetTier;
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

/** The handoff point to pre-production-service: {@code id} is exactly the {@code lockedIdeaId}
 * pre-production-service's {@code POST /v1/projects/from-locked-idea} expects as an external
 * reference -- no local FK back, no synchronous call out. This service's job ends here.
 * <p>
 * Two entry points, same handoff artifact: {@code sessionId} is set when this idea came out of
 * a campaign-planning chat session; {@code projectRequirementId} is set when it came from the
 * standalone-brief path's idea generation instead. Exactly one is ever non-null (enforced by
 * {@code chk_locked_idea_origin}). */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "locked_idea")
public class LockedIdea {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "session_id", unique = true)
    private UUID sessionId;

    @Column(name = "project_requirement_id")
    private UUID projectRequirementId;

    /** Set once this idea has been synchronously handed to pre-production-service --
     * see ProjectRequirementIdeaService.lockOption. Null until then. */
    @Column(name = "project_id")
    private UUID projectId;

    @Column(name = "title", nullable = false, length = 240)
    private String title;

    @Column(name = "concept", columnDefinition = "text")
    private String concept;

    @Column(name = "target_audience", columnDefinition = "text")
    private String targetAudience;

    @Column(name = "campaign_angle", columnDefinition = "text")
    private String campaignAngle;

    @Column(name = "key_message", columnDefinition = "text")
    private String keyMessage;

    @Column(name = "tone", length = 240)
    private String tone;

    @Enumerated(EnumType.STRING)
    @Column(name = "budget_tier", nullable = false, length = 16)
    private BudgetTier budgetTier;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
