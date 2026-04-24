package com.dalai.llama.billing.service;

import com.dalai.llama.billing.service.impl.PaymentServiceImpl;

import java.math.BigDecimal;
import java.util.UUID;

public interface PaymentService {

    UUID createPayment(UUID tenantId, BigDecimal amount, String description);

    UUID createPayment(UUID tenantId, String currency,BigDecimal amount, String description, UUID subscriptionId);

    void handlePaymentSuccess(String gatewayOrderId, String paymentId, String signature);

    PaymentServiceImpl.SubscriptionPaymentResult createSubscriptionPayment(
            UUID tenantId,
            String planCode,
            BigDecimal planAmount,
            BigDecimal walletCredit,
            UUID subscriptionId
    );

    record SubscriptionPaymentResult(
            UUID paymentId,
            String gatewayOrderId,
            BigDecimal totalAmount,
            BigDecimal planAmount,
            BigDecimal walletCredit,
            String currency
    ) {}
}
