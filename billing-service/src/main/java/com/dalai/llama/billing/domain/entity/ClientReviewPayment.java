package com.dalai.llama.billing.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

/** A client's payment to lock a reviewed creative package. Dedicated table (not the generic
 * {@code Payment}) since this is money FROM a client, not a wallet top-up. On success the
 * {@code creatorAmount} (the creator's margin) credits the creator's wallet; {@code settled}
 * marks whether the platform's {@code platformBase} take has been paid out (not yet processed). */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "client_review_payment")
public class ClientReviewPayment {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "review_token", nullable = false, length = 128)
    private String reviewToken;

    /** 'LOCK' or 'EXTRA_REVIEW' -- which action a webhook-confirmed capture of this payment
     * should trigger on pre-production-service (see RazorpayWebhookOrchestrationService). Not an
     * enum for the same reason {@link #status} isn't: this row's own convention already predates
     * one. */
    @Column(name = "kind", nullable = false, length = 16)
    private String kind;

    @Column(name = "platform_base", nullable = false, precision = 15, scale = 2)
    private BigDecimal platformBase;

    @Column(name = "creator_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal creatorAmount;

    @Column(name = "total_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "currency", nullable = false, length = 8)
    private String currency;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "gateway_order_id", length = 128)
    private String gatewayOrderId;

    @Column(name = "gateway_payment_id", length = 128)
    private String gatewayPaymentId;

    @Column(name = "settled", nullable = false)
    private boolean settled;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
