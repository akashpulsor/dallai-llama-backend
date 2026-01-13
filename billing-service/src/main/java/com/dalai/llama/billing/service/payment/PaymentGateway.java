package com.dalai.llama.billing.service.payment;

import java.math.BigDecimal;

public interface PaymentGateway {

    /**
     * Creates a payment order with the external gateway.
     *
     * @param amount   amount in major currency units (e.g. INR, USD)
     * @param currency ISO-4217 currency code (INR, USD, EUR)
     * @param receipt  merchant receipt/reference
     * @return gateway order ID
     */
    String createOrder(BigDecimal amount, String currency, String receipt);

    /**
     * Verifies payment authenticity (signature, checksum, etc.)
     *
     * @throws RuntimeException if verification fails
     */
    void verify(String orderId, String paymentId, String signature);
}
