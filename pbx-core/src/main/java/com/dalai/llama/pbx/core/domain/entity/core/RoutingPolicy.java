package com.dalai.llama.pbx.core.domain.entity.core;


import com.dalai.llama.pbx.core.domain.enums.RoutingActionType;
import com.dalai.llama.pbx.core.domain.enums.RoutingMatchType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Inbound call routing rules.
 *
 * Evaluated by CallAuthorizationService during Kamailio authorize/inbound.
 * Ordered by priority DESC — first match wins.
 *
 * time_condition example: {"days":[1,2,3,4,5],"start":"09:00","end":"17:00"}
 * action_target: queue UUID, agent UUID, IVR UUID, bot name, external number
 */
@Entity
@Table(name = "routing_policies")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class RoutingPolicy {

    @Id
    @Builder.Default
    private UUID id = UUID.randomUUID();

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "subscription_id", nullable = false)
    private UUID subscriptionId;

    @Column(nullable = false, length = 100)
    private String name;

    @Builder.Default
    private Integer priority = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "match_type", length = 20)
    @Builder.Default
    private RoutingMatchType matchType = RoutingMatchType.DID;

    @Column(name = "match_value", length = 100)
    private String matchValue;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", nullable = false, length = 30)
    private RoutingActionType actionType;

    @Column(name = "action_target", nullable = false, length = 200)
    private String actionTarget;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "time_condition", columnDefinition = "jsonb")
    private Map<String, Object> timeCondition;

    @Column(name = "is_active")
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "created_at")
    private Instant createdAt;

    @PrePersist
    void prePersist() { createdAt = Instant.now(); }
}
