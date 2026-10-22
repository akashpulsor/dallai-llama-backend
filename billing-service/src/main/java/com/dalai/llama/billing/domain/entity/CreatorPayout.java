package com.dalai.llama.billing.domain.entity;

import com.dalai.llama.billing.domain.entity.enums.CreatorPayoutStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** A project's payout to its creator -- see {@link CreatorPayoutStatus} for the lifecycle and
 * CreatorPayoutService for how {@code amount} is reached. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "creator_payout")
public class CreatorPayout {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "project_id", nullable = false, unique = true)
    private UUID projectId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private CreatorPayoutStatus status;

    /** What the client paid that reached the creator's wallet: the brief's upfront payment plus
     * every captured review-page payment's wallet credit. */
    @Column(name = "client_paid", precision = 15, scale = 2)
    private BigDecimal clientPaid;

    /** The project's AI usage as charged to the wallet -- provider cost plus the platform's margin. */
    @Column(name = "production_charged", precision = 15, scale = 2)
    private BigDecimal productionCharged;

    /** Razorpay's fee (GST included) on each of the client's payments. */
    @Column(name = "gateway_fees", precision = 15, scale = 2)
    private BigDecimal gatewayFees;

    @Column(name = "amount", precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 8)
    private String currency;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "fund_account_id", length = 64)
    private String fundAccountId;

    @Column(name = "gateway_payout_id", length = 64)
    private String gatewayPayoutId;

    @Column(name = "utr", length = 64)
    private String utr;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "paid_at")
    private Instant paidAt;

    /** The RazorpayX reference and idempotency key of the current attempt. */
    public String attemptReference() {
        return id + "-" + attempts;
    }

    public void moveTo(CreatorPayoutStatus next, String reason) {
        this.status = next;
        this.failureReason = reason == null ? null : reason.substring(0, Math.min(reason.length(), 500));
        this.updatedAt = Instant.now();
    }
}
