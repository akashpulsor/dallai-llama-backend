package com.dalai.llama.billing.domain.entity;

import com.dalai.llama.billing.domain.entity.enums.PaymentStatus;
import com.dalai.llama.billing.domain.event.PaymentReceivedEvent;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
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

    /* =========================
       IDENTIFIERS
       ========================= */

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    private UUID walletId;
    private UUID paymentMethodId;

    /* =========================
       AMOUNT & CURRENCY
       ========================= */

    @Column(nullable = false, precision = 15, scale = 4)
    private BigDecimal amount;

    /**
     * ISO-4217 currency code (e.g. INR, USD, EUR)
     * Must come from Wallet at creation time
     */
    @Column(nullable = false, length = 3)
    private String currency;

    /* =========================
       GATEWAY DETAILS
       ========================= */

    @Column(nullable = false, length = 50)
    private String gateway; // RAZORPAY, STRIPE, etc.

    @Column(nullable = false, unique = true, length = 100)
    private String gatewayOrderId;

    private String gatewayPaymentId;
    private String gatewaySignature;

    /* =========================
       STATUS
       ========================= */

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus status;

    private String failureReason;

    /* =========================
       METADATA
       ========================= */

    @Column(length = 500)
    private String description;

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
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    /* =========================
       DOMAIN STATE TRANSITIONS
       ========================= */

    public void markSuccess(String paymentId, String signature) {
        this.gatewayPaymentId = paymentId;
        this.gatewaySignature = signature;
        this.status = PaymentStatus.SUCCESS;
        this.updatedAt = Instant.now();
    }

    public void markFailed(String reason) {
        this.status = PaymentStatus.FAILED;
        this.failureReason = reason;
        this.updatedAt = Instant.now();
    }

    public void markRefunded(String reason) {
        this.status = PaymentStatus.REFUNDED;
        this.failureReason = reason;
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
