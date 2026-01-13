package com.dalai.llama.billing.service;

import java.math.BigDecimal;
import java.util.UUID;

public interface PaymentService {

    UUID createPayment(UUID tenantId, BigDecimal amount, String description);

    void handlePaymentSuccess(String gatewayOrderId, String paymentId, String signature);
}
