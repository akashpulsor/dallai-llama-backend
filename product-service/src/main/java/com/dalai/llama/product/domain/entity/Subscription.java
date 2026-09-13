package com.dalai.llama.product.domain.entity;

import com.dalai.llama.product.domain.entity.enums.BillingCycle;
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

    @Column(name = "tenant_app_id")
    private UUID tenantAppId;

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

    // Requested DID info (snapshot from subscribe request, used during provisioning)
    @Column(name = "requested_did_number")
    private String requestedDidNumber;

    @Column(name = "requested_did_country")
    private String requestedDidCountry;

    @Column(name = "requested_did_region")
    private String requestedDidRegion;

    @Column(name = "requested_did_city")
    private String requestedDidCity;

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

    @Column(name = "paused_at")
    private Instant pausedAt;

    // Snapshot from Plan.billingCycle at subscribe time -- a later plan price/cycle change
    // shouldn't retroactively change an already-active subscription's renewal cadence. Null for
    // every product that doesn't model a cycle yet (see BillingCycle's javadoc).
    @Enumerated(EnumType.STRING)
    @Column(name = "billing_cycle", length = 20)
    private BillingCycle billingCycle;

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
        this.expiresAt = Instant.now().plus(cycleDuration());
    }

    public void suspend() {
        this.status = SubscriptionStatus.SUSPENDED;
        this.suspendedAt = Instant.now();
    }

    public void cancel() {
        this.status = SubscriptionStatus.CANCELLED;
        this.cancelledAt = Instant.now();
    }

    /** User-initiated hold -- see {@link SubscriptionStatus#PAUSED} javadoc. Only valid from
     * ACTIVE; billing/expiry tracking stops until {@link #resume()}. */
    public void pause() {
        if (status != SubscriptionStatus.ACTIVE) {
            throw new IllegalStateException("Cannot pause a subscription in status " + status);
        }
        this.status = SubscriptionStatus.PAUSED;
        this.pausedAt = Instant.now();
    }

    /** Resumes from a pause with a fresh cycle starting now -- no proration of the paused time,
     * matching the product decision to keep pause/resume simple rather than track remaining days. */
    public void resume() {
        if (status != SubscriptionStatus.PAUSED) {
            throw new IllegalStateException("Cannot resume a subscription in status " + status);
        }
        this.status = SubscriptionStatus.ACTIVE;
        this.pausedAt = null;
        this.expiresAt = Instant.now().plus(cycleDuration());
    }

    /** A renewal charge failed -- see {@link SubscriptionStatus#PAST_DUE} javadoc. Entitlements
     * drop immediately (the entitlement resolver only grants paid entitlements to ACTIVE
     * subscriptions); the recurring charge itself keeps retrying independently in billing-service,
     * and a later successful retry calls {@link #activate()} again to recover. */
    public void markPastDue() {
        this.status = SubscriptionStatus.PAST_DUE;
    }

    private java.time.Duration cycleDuration() {
        if (billingCycle == null) {
            return java.time.Duration.ofDays(30); // Unchanged default for products with no cycle modeled yet.
        }
        return switch (billingCycle) {
            case MONTHLY -> java.time.Duration.ofDays(30);
            case QUARTERLY -> java.time.Duration.ofDays(91);
            case YEARLY -> java.time.Duration.ofDays(365);
        };
    }

    public boolean isActive() {
        return status == SubscriptionStatus.ACTIVE;
    }

    public boolean isFullyProvisioned() {
        return didProvisioned && sipEndpointCreated && channelsAllocated;
    }
}