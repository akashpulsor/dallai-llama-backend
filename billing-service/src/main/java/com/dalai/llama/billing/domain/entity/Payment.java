package com.dalai.llama.billing.domain.entity;

import com.dalai.llama.billing.domain.entity.enums.PaymentStatus;
import com.dalai.llama.billing.domain.event.PaymentReceivedEvent;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Entity
@Table(
        name = "payments",
        indexes = {
                @Index(name = "idx_payment_tenant", columnList = "tenantId"),
                @Index(name = "idx_payment_status", columnList = "status"),
                @Index(name = "idx_payment_gateway_order", columnList = "gatewayOrderId")
        }
)
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Payment {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    private UUID walletId;

    @Column(name = "subscription_id")
    private UUID subscriptionId;

    private UUID paymentMethodId;

    @Column(nullable = false, precision = 15, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false, length = 50)
    private String gateway;

    @Column(nullable = false, unique = true, length = 100)
    private String gatewayOrderId;

    private String gatewayPaymentId;
    private String gatewaySignature;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus status;

    private String failureReason;

    @Column(length = 500)
    private String description;

    private Instant expiredAt;
    private Instant createdAt;
    private Instant updatedAt;

    @Version
    private Long version;

    /* =========================
       DOMAIN FACTORY
       ========================= */

    public static Payment create(
            UUID tenantId,
            UUID walletId,
            BigDecimal amount,
            String currency,
            String gateway,
            String gatewayOrderId,
            String description
    ) {
        if (currency == null || currency.length() != 3) {
            throw new IllegalArgumentException("Invalid currency: " + currency);
        }

        Instant now = Instant.now();

        return Payment.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .walletId(walletId)
                .amount(amount)
                .currency(currency)
                .gateway(gateway)
                .gatewayOrderId(gatewayOrderId)
                .status(PaymentStatus.PENDING)
                .description(description)
                .expiredAt(now.plus(15, ChronoUnit.MINUTES))
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    /* =========================
       DOMAIN STATE TRANSITIONS
       Returns previous status for event recording
       ========================= */

    public PaymentStatus markSuccess(String paymentId, String signature) {
        PaymentStatus previous = this.status;
        if (this.status != PaymentStatus.PENDING && this.status != PaymentStatus.PROCESSING) {
            throw new IllegalStateException("Cannot mark SUCCESS from " + this.status);
        }
        this.gatewayPaymentId = paymentId;
        this.gatewaySignature = signature;
        this.status = PaymentStatus.SUCCESS;
        this.updatedAt = Instant.now();
        return previous;
    }

    public PaymentStatus markFailed(String reason) {
        PaymentStatus previous = this.status;
        if (this.status == PaymentStatus.SUCCESS || this.status == PaymentStatus.REFUNDED) {
            throw new IllegalStateException("Cannot mark FAILED from " + this.status);
        }
        this.status = PaymentStatus.FAILED;
        this.failureReason = reason;
        this.updatedAt = Instant.now();
        return previous;
    }

    public PaymentStatus markRefunded(String reason) {
        PaymentStatus previous = this.status;
        if (this.status != PaymentStatus.SUCCESS) {
            throw new IllegalStateException("Cannot REFUND from " + this.status);
        }
        this.status = PaymentStatus.REFUNDED;
        this.failureReason = reason;
        this.updatedAt = Instant.now();
        return previous;
    }

    /* =========================
       HELPERS
       ========================= */

    public boolean isSubscriptionPayment() {
        return this.description != null && this.description.startsWith("SUBSCRIPTION:");
    }

    @Setter
    private UUID subscriptionIdSetter; // not needed, use builder pattern below

    /**
     * Link payment to subscription after creation
     */
    public void linkSubscription(UUID subscriptionId) {
        this.subscriptionId = subscriptionId;
        this.updatedAt = Instant.now();
    }

    /* =========================
       DOMAIN → EVENT
       ========================= */

    public PaymentReceivedEvent toEvent() {
        return PaymentReceivedEvent.builder()
                .tenantId(this.tenantId)
                .paymentId(this.id)
                .amount(this.amount)
                .currency(this.currency)
                .gateway(this.gateway)
                .occurredAt(Instant.now())
                .build();
    }
}