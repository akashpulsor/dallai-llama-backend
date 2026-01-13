package com.dalai.llama.pbx.core.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.List;

@Entity
@Table(name = "routing_policies",
       uniqueConstraints = @UniqueConstraint(columnNames = {"tenantId","entrypoint"}))
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class RoutingPolicy {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false,name = "tenant_id", length = 36)
    private String tenantId;

    @Column(nullable = false)
    private String entrypoint; // like team:support

    @Column(nullable = false)
    private String strategy;

    private String teamId;

    @ElementCollection
    private List<String> skills;

    @ElementCollection
    private List<String> failover;

    private String aiEndpoint;
    private Integer aiTimeoutMs;

    private Integer version;

    private Instant updatedAt;
}
