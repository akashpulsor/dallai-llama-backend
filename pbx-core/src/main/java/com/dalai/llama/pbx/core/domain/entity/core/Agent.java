package com.dalai.llama.pbx.core.domain.entity.core;


import com.dalai.llama.pbx.core.domain.enums.AgentRole;
import com.dalai.llama.pbx.core.domain.enums.AgentStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Contact center agent.
 *
 * Creating an agent (via /api/v1/agents) also INSERTs a subscriber row
 * with matching username/domain + computed HA1/HA1B. This is handled by
 * AgentService, not by JPA cascade — the two tables have different PKs
 * (UUID vs SERIAL) and different lifecycles.
 *
 * username + sip_domain must match a subscriber row for SIP auth to work.

 * Contact center agent.
 *
 * Creating an agent (via /api/v1/agents) also INSERTs a subscriber row
 * with matching username/domain + computed HA1/HA1B. This is handled by
 * AgentService, not by JPA cascade — the two tables have different PKs
 * (UUID vs SERIAL) and different lifecycles.
 *
 * username + sip_domain must match a subscriber row for SIP auth to work.
 */
@Entity
@Table(name = "agents", uniqueConstraints = {
        @UniqueConstraint(name = "uk_agents_tenant_username", columnNames = {"tenant_id", "username"}),
        @UniqueConstraint(name = "uk_agents_tenant_extension", columnNames = {"tenant_id", "extension"})
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Agent {

    @Id
    @Builder.Default
    private UUID id = UUID.randomUUID();

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "subscription_id", nullable = false)
    private UUID subscriptionId;

    @Column(nullable = false, length = 64)
    private String username;

    @Column(name = "sip_domain", nullable = false, length = 100)
    private String sipDomain;

    @Column(name = "display_name", length = 100)
    private String displayName;

    @Column(length = 10)
    private String extension;

    @Column(length = 100)
    private String email;

    @Column(name = "keycloak_user_id", length = 100)
    private String keycloakUserId;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    @Builder.Default
    private AgentRole role = AgentRole.AGENT;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    @Builder.Default
    private AgentStatus status = AgentStatus.OFFLINE;

    @Column(name = "max_concurrent_calls")
    @Builder.Default
    private Integer maxConcurrentCalls = 1;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    @Builder.Default
    private List<String> skills = List.of();

    @Column(name = "is_active")
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    void prePersist() { createdAt = updatedAt = Instant.now(); }

    @PreUpdate
    void preUpdate() { updatedAt = Instant.now(); }
}