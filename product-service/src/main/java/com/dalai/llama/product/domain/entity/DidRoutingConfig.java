package com.dalai.llama.product.domain.entity;


import com.dalai.llama.product.domain.entity.enums.RoutingTargetType;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalTime;
import java.util.UUID;

@Entity
@Table(
        name = "did_routing_configs",
        uniqueConstraints = @UniqueConstraint(name = "uk_routing_did", columnNames = "did_id")
)
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DidRoutingConfig {

    @Id
    private UUID id;

    @OneToOne
    @JoinColumn(name = "did_id", nullable = false)
    private Did did;

    @Enumerated(EnumType.STRING)
    private RoutingTargetType routingTargetType;

    private String routingTargetId;

    @Enumerated(EnumType.STRING)
    private RoutingTargetType fallbackTargetType;

    private String fallbackTargetId;

    private boolean recordingEnabled;
    private boolean transcriptionEnabled;
    private boolean sentimentEnabled;

    private boolean businessHoursOnly;
    private LocalTime businessHoursStart;
    private LocalTime businessHoursEnd;

    @Enumerated(EnumType.STRING)
    private RoutingTargetType afterHoursTargetType;

    private String afterHoursTargetId;

    private int maxRingTime;
    private int priority;

    private Instant createdAt;
    private Instant updatedAt;

    @Version
    private long version;
}
