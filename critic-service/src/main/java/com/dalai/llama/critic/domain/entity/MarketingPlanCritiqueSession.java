package com.dalai.llama.critic.domain.entity;

import com.dalai.llama.critic.domain.CritiqueVerdict;
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

/** One pre-flight review of one marketing plan -- the marketing-plan sibling of {@link
 * CritiqueSession}, reusing the same {@link CritiqueVerdict} vocabulary. {@code planId} is an
 * external reference into creative-planning-service's own {@code MarketingPlan}, never a local
 * FK -- same convention as {@code shotId} on the shot-critique session. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "marketing_plan_critique_session")
public class MarketingPlanCritiqueSession {

    /** Assigned by the caller (not @GeneratedValue), same reason as {@code CritiqueSession}: the
     * orchestrator logs thought entries against this id before the session row itself exists. */
    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "plan_id", nullable = false)
    private UUID planId;

    @Enumerated(EnumType.STRING)
    @Column(name = "verdict", nullable = false, length = 24)
    private CritiqueVerdict verdict;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
