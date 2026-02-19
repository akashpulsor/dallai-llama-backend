package com.dalai.llama.product.domain.entity;

import com.dalai.llama.product.domain.entity.enums.SubscriptionStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "subscriptions",
        uniqueConstraints = @UniqueConstraint(name = "uk_subscription_tenant_product",
                columnNames = {"tenant_id", "product_id"}),
        indexes = {
                @Index(name = "idx_subscription_tenant", columnList = "tenant_id"),
                @Index(name = "idx_subscription_status", columnList = "status")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Subscription {

    @Id
    @Builder.Default
    private UUID id = UUID.randomUUID();

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "plan_id", nullable = false)
    private Plan plan;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Builder.Default
    private SubscriptionStatus status = SubscriptionStatus.PENDING_PAYMENT;

    // Resource IDs
    @Column(name = "did_id")
    private UUID didId;

    @Column(name = "sip_endpoint_id")
    private UUID sipEndpointId;

    @Column(name = "channel_bundle_id")
    private UUID channelBundleId;

    @Column(name = "plan_assignment_id")
    private UUID planAssignmentId;

    @Column(name = "tenant_sip_trunk_id")
    private UUID tenantSipTrunkId;

    // Provisioning flags
    @Column(name = "did_provisioned")
    @Builder.Default
    private boolean didProvisioned = false;

    @Column(name = "sip_endpoint_created")
    @Builder.Default
    private boolean sipEndpointCreated = false;

    @Column(name = "channels_allocated")
    @Builder.Default
    private boolean channelsAllocated = false;

    // Config
    @Column(name = "agent_seats", nullable = false)
    private int agentSeats;

    @Column(name = "included_minutes")
    @Builder.Default
    private int includedMinutes = 0;

    // Timestamps
    @Column(name = "subscribed_at", nullable = false)
    private Instant subscribedAt;

    @Column(name = "activated_at")
    private Instant activatedAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "suspended_at")
    private Instant suspendedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Version
    private long version;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
        if (subscribedAt == null) subscribedAt = Instant.now();
        createdAt = updatedAt = Instant.now();
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    public void markPendingProvision() {
        this.status = SubscriptionStatus.PENDING_PROVISION;
    }

    public void markDidProvisioned(UUID didId) {
        this.didProvisioned = true;
        this.didId = didId;
    }

    public void markSipEndpointCreated(UUID sipEndpointId) {
        this.sipEndpointCreated = true;
        this.sipEndpointId = sipEndpointId;
    }

    public void markChannelsAllocated(UUID channelBundleId) {
        this.channelsAllocated = true;
        this.channelBundleId = channelBundleId;
    }

    public void activate() {
        this.status = SubscriptionStatus.ACTIVE;
        this.activatedAt = Instant.now();
        this.expiresAt = Instant.now().plusSeconds(30L * 24 * 60 * 60);
    }

    public void suspend() {
        this.status = SubscriptionStatus.SUSPENDED;
        this.suspendedAt = Instant.now();
    }

    public void cancel() {
        this.status = SubscriptionStatus.CANCELLED;
        this.cancelledAt = Instant.now();
    }

    public boolean isActive() {
        return status == SubscriptionStatus.ACTIVE;
    }

    public boolean isFullyProvisioned() {
        return didProvisioned && sipEndpointCreated && channelsAllocated;
    }
}