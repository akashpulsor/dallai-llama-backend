package com.dalai.llama.product.domain.entity;


import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "outbound_caller_ids",
        indexes = @Index(name = "idx_cli_tenant", columnList = "tenantId")
)
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OutboundCallerId {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sip_trunk_id")
    private SipTrunk sipTrunk;

    @Column(nullable = false)
    private String number;

    private String displayName;

    private boolean verified;
    private boolean isDefault;

    private Instant createdAt;
    private Instant updatedAt;

    @Version
    private long version;
}
