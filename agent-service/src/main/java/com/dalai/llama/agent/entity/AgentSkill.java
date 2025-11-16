package com.dalai.llama.agent.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "agent_skills")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AgentSkill {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "agent_id", nullable = false)
    private Long agentId;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "skill_name", nullable = false)
    private String skillName;

    @Column(name = "skill_level")
    @Builder.Default
    private Integer skillLevel = 1;

    @Column(name = "is_primary")
    @Builder.Default
    private Boolean isPrimary = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private OffsetDateTime updatedAt = OffsetDateTime.now();
}
