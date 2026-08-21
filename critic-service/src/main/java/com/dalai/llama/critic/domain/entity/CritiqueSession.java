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

/** One pre-flight review of one shot plan. The plan itself (original, and revised when the
 * revision planner ran) is persisted as relational rows in {@link CritiquePlanSnapshot} + its
 * child tables, not as JSON on this entity -- see that class's javadoc. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "critique_session")
public class CritiqueSession {

    /** Assigned by the caller (not @GeneratedValue) -- {@code CritiqueOrchestrator} generates this
     * id upfront and uses it to correlate {@code CritiqueThought} log rows written before the
     * session itself is ever persisted. */
    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "shot_id", nullable = false)
    private UUID shotId;

    @Enumerated(EnumType.STRING)
    @Column(name = "verdict", nullable = false, length = 24)
    private CritiqueVerdict verdict;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
