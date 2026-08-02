package com.dalai.llama.billing.service;

import com.dalai.llama.billing.service.impl.PaymentServiceImpl;

import java.math.BigDecimal;
import java.util.UUID;

public interface PaymentService {

    UUID createPayment(UUID tenantId, BigDecimal amount, String description);

    UUID createPayment(UUID tenantId, String currency, BigDecimal amount,
                       String description, UUID subscriptionId);

    PaymentOrderResult createPaymentOrder(UUID tenantId, String currency, BigDecimal amount,
                                          String description, UUID subscriptionId);

    void handlePaymentSuccess(String gatewayOrderId, String paymentId, String signature);

    void handlePaymentSuccess(UUID tenantId, UUID paymentId,
                              String gatewayOrderId, String gatewayPaymentId, String signature);

    PaymentServiceImpl.SubscriptionPaymentResult createSubscriptionPayment(
            UUID tenantId,
            String planCode,
            BigDecimal planAmount,
            BigDecimal walletCredit,
            UUID subscriptionId
    );

    /**
     * Refund a subscription wallet payment back to the tenant's wallet.
     *
     * Used when subscription activation fails AFTER the wallet was debited
     * for the subscription. Credits the wallet the original debited amount,
     * marks the Payment as REFUNDED, and records the audit PaymentEvent.
     *
     * Idempotent — safe under Kafka redelivery. Repeated calls for an
     * already-REFUNDED payment return silently.
     *
     * NOT a Razorpay refund. This is purely platform-internal:
     * wallet was debited, activation failed, wallet gets credited back.
     * Razorpay refunds (cash back to bank) flow through {@link RefundService}.
     *
     * Future: a (paymentId, reason) overload may be added if a caller has
     * only the paymentId without tenant/subscription context.
     *
     * @param tenantId       must match payment.tenantId — mismatch throws
     * @param paymentId      the wallet payment to refund (gateway = "WALLET")
     * @param subscriptionId carried through to the wallet transaction record
     * @param reason         human-readable reason recorded on the PaymentEvent
     */
    void refundSubscriptionPayment(UUID tenantId, UUID paymentId,
                                   UUID subscriptionId, String reason);

    record SubscriptionPaymentResult(
            UUID paymentId,
            String gatewayOrderId,
            BigDecimal totalAmount,
            BigDecimal planAmount,
            BigDecimal walletCredit,
            String currency
    ) {}

    record PaymentOrderResult(
            UUID paymentId,
            String gatewayOrderId,
            BigDecimal amount,
            String currency,
            String keyId,
            String status
    ) {}
}
