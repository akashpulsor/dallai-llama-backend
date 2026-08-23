package com.dalai.llama.billing.service;

import com.dalai.llama.billing.dto.response.ProjectRequirementFundingView;
import com.dalai.llama.billing.service.impl.PaymentServiceImpl;

import java.math.BigDecimal;
import java.util.UUID;

public interface PaymentService {

    UUID createPayment(UUID tenantId, BigDecimal amount, String description);

    UUID createPayment(UUID tenantId, String currency, BigDecimal amount,
                       String description, UUID subscriptionId);

    PaymentOrderResult createPaymentOrder(UUID tenantId, String currency, BigDecimal amount,
                                          String description, UUID subscriptionId);

    /**
     * Same order-creation path as {@link #createPaymentOrder}, scoped to a specific
     * creative-planning-service ProjectRequirement instead of (or alongside) a subscription.
     * The wallet is still credited on success like any other payment -- this doesn't create a
     * second ledger, it tags which brief the money was for.
     */
    PaymentOrderResult createProjectRequirementPaymentOrder(UUID tenantId, UUID projectRequirementId,
                                                             String currency, BigDecimal amount,
                                                             String description);

    /**
     * Every payment ever created against one brief, and how much of it actually succeeded.
     * Derived entirely from {@code payments} rows -- there's nothing else to keep in sync.
     */
    ProjectRequirementFundingView getProjectRequirementFunding(UUID tenantId, UUID projectRequirementId);

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
