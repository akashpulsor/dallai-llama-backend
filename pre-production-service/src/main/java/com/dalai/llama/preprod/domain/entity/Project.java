package com.dalai.llama.preprod.domain.entity;

import com.dalai.llama.preprod.domain.BudgetTier;
import com.dalai.llama.preprod.domain.ProjectStatus;
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

/**
 * Root aggregate of pre-production-service, always created from a locked idea handed off by
 * creative-planning-service ({@link #lockedIdeaId} is an external reference, never a local FK --
 * that service is out of this module's bounded context).
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "project")
public class Project {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "locked_idea_id", nullable = false)
    private UUID lockedIdeaId;

    @Enumerated(EnumType.STRING)
    @Column(name = "budget_tier", nullable = false, length = 16)
    private BudgetTier budgetTier;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private ProjectStatus status;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
