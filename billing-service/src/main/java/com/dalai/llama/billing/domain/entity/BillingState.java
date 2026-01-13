package com.dalai.llama.billing.domain.entity;

import com.dalai.llama.billing.domain.entity.enums.BillingStateType;
import com.dalai.llama.billing.domain.event.BillingStateChangedEvent;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "billing_states")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BillingState {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true)
    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BillingStateType state;

    private Instant graceExpiresAt;
    private String blockReason;
    private Instant blockedAt;

    private Instant lastCheckedAt;
    private Instant createdAt;
    private Instant updatedAt;

    @Version
    private Long version;

    /* =========================
       DOMAIN FACTORY
       ========================= */

    public static BillingState createDefault(UUID tenantId) {
        Instant now = Instant.now();
        return BillingState.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .state(BillingStateType.ACTIVE)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    /* =========================
       DOMAIN BEHAVIOR
       ========================= */

    public void enterGrace(Instant graceExpiry) {
        this.state = BillingStateType.GRACE;
        this.graceExpiresAt = graceExpiry;
        this.lastCheckedAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public void block(String reason) {
        this.state = BillingStateType.BLOCKED;
        this.blockReason = reason;
        this.blockedAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public void activate() {
        this.state = BillingStateType.ACTIVE;
        this.graceExpiresAt = null;
        this.blockReason = null;
        this.blockedAt = null;
        this.updatedAt = Instant.now();
    }

    /* =========================
       DOMAIN → EVENT
       ========================= */

    public BillingStateChangedEvent toEvent(BillingStateType previousState) {
        return BillingStateChangedEvent.builder()
                .tenantId(this.tenantId)
                .previousState(previousState)
                .currentState(this.state)
                .occurredAt(Instant.now())
                .build();
    }
}
