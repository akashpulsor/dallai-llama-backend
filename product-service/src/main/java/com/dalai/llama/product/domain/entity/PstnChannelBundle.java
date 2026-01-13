package com.dalai.llama.product.domain.entity;


import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "pstn_channel_bundles",
        indexes = {
                @Index(name = "idx_channel_tenant", columnList = "tenant_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PstnChannelBundle {

    @Id
    @Column(nullable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sip_trunk_id")
    private SipTrunk sipTrunk;

    @Column(name = "total_channels", nullable = false)
    private int totalChannels;

    @Column(name = "inbound_channels", nullable = false)
    private int inboundChannels;

    @Column(name = "outbound_channels", nullable = false)
    private int outboundChannels;

    @Column(name = "active_channels")
    private int activeChannels;

    /**
     * ACTIVE | EXHAUSTED | SUSPENDED
     */
    @Column(nullable = false)
    private String status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;
}
