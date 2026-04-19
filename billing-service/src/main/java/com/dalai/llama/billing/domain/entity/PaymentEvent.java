package com.dalai.llama.billing.domain.entity;

import com.dalai.llama.billing.domain.entity.enums.PaymentStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Records every state transition in a payment's lifecycle.
 * Provides full audit trail: PENDING → PROCESSING → SUCCESS/FAILED → REFUNDED
 */
@Entity
@Table(name = "payment_events", indexes = {
        @Index(name = "idx_pe_payment_id", columnList = "payment_id"),
        @Index(name = "idx_pe_tenant_id", columnList = "tenant_id")
})
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentEvent {

    @Id
    private UUID id;

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 20)
    private PaymentStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 20)
    private PaymentStatus toStatus;

    @Column(name = "gateway_order_id", length = 100)
    private String gatewayOrderId;

    @Column(name = "gateway_payment_id", length = 100)
    private String gatewayPaymentId;

    @Column(length = 500)
    private String reason;

    @Column(length = 50)
    private String source; // SYSTEM, WEBHOOK, USER, SCHEDULER

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public static PaymentEvent record(Payment payment, PaymentStatus fromStatus,
                                      PaymentStatus toStatus, String reason, String source) {
        return PaymentEvent.builder()
                .id(UUID.randomUUID())
                .paymentId(payment.getId())
                .tenantId(payment.getTenantId())
                .fromStatus(fromStatus)
                .toStatus(toStatus)
                .gatewayOrderId(payment.getGatewayOrderId())
                .gatewayPaymentId(payment.getGatewayPaymentId())
                .reason(reason)
                .source(source)
                .createdAt(Instant.now())
                .build();
    }
}