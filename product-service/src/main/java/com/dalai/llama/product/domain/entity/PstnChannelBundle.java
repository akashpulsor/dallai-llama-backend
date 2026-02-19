package com.dalai.llama.product.domain.entity;

import com.dalai.llama.product.domain.entity.enums.ChannelDirection;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "pstn_channel_bundles",
        indexes = {
                @Index(name = "idx_channel_tenant", columnList = "tenant_id")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PstnChannelBundle {

    @Id
    @Builder.Default
    private UUID id = UUID.randomUUID();

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "subscription_id")
    private UUID subscriptionId;

    @Column(name = "product_code", length = 50)
    private String productCode;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sip_trunk_id")
    private SipTrunk sipTrunk;

    // ==================== Channel Config ====================

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false)
    private ChannelDirection direction;

    @Column(name = "total_channels", nullable = false)
    private int totalChannels;

    /**
     * Only set if direction = BOTH or INBOUND
     */
    @Column(name = "inbound_channels")
    private Integer inboundChannels;

    /**
     * Only set if direction = BOTH or OUTBOUND
     */
    @Column(name = "outbound_channels")
    private Integer outboundChannels;

    @Column(name = "active_channels")
    @Builder.Default
    private int activeChannels = 0;

    // ==================== Status ====================

    @Column(length = 20)
    @Builder.Default
    private String status = "ACTIVE";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Version
    private long version;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
        createdAt = updatedAt = Instant.now();
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}