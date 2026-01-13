package com.dalai.llama.tenant.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "agent_capacities",
        uniqueConstraints = @UniqueConstraint(columnNames = "tenant_id"))
@Getter
@Setter
public class AgentCapacity {

    @Id
    @UuidGenerator
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    private int licensedAgents = 5;
    private int activeAgents;
    private int licensedSupervisors = 1;
    private int activeSupervisors;
    private int maxConcurrentLogins = 5;

    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    @Version
    private long version;

    @PrePersist
    void onCreate() {
        createdAt = OffsetDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
