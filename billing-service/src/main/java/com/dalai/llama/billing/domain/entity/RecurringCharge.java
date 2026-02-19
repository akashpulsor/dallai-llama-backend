package com.dalai.llama.billing.domain.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Recurring charge for auto-billing.
 * Created on subscription, charged monthly by scheduler.
 *
 * Types:
 * - PLATFORM_FEE: Monthly plan fee
 * - DID_RENTAL: Monthly DID rental
 * - AGENT_FEE: Per-agent monthly fee
 */
@Entity
@Table(name = "recurring_charges", indexes = {
        @Index(name = "idx_recurring_tenant", columnList = "tenant_id"),
        @Index(name = "idx_recurring_next_date", columnList = "next_charge_date"),
        @Index(name = "idx_recurring_status", columnList = "status")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RecurringCharge {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(nullable = false, length = 30)
    private String type; // PLATFORM_FEE, DID_RENTAL, AGENT_FEE

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String frequency = "MONTHLY"; // MONTHLY, WEEKLY, YEARLY

    @Column(name = "next_charge_date", nullable = false)
    private LocalDate nextChargeDate;

    @Column(name = "last_charged_at")
    private Instant lastChargedAt;

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = "ACTIVE"; // ACTIVE, PAUSED, CANCELLED

    @Column(name = "source_type", length = 30)
    private String sourceType; // DID, PLAN, AGENT

    @Column(name = "source_id")
    private UUID sourceId;

    private String description;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    private UUID subscriptionId; // Optional link to subscription (for platform fees)
    @Version
    private long version;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now();
        if (nextChargeDate == null) nextChargeDate = LocalDate.now().plusMonths(1);
        updatedAt = Instant.now();
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    public void markCharged() {
        this.lastChargedAt = Instant.now();
        this.nextChargeDate = calculateNextChargeDate();
    }

    public void cancel() {
        this.status = "CANCELLED";
    }

    public void pause() {
        this.status = "PAUSED";
    }

    public void resume() {
        this.status = "ACTIVE";
    }

    private LocalDate calculateNextChargeDate() {
        return switch (frequency) {
            case "WEEKLY" -> nextChargeDate.plusWeeks(1);
            case "YEARLY" -> nextChargeDate.plusYears(1);
            default -> nextChargeDate.plusMonths(1); // MONTHLY
        };
    }
}