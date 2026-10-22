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

    /**
     * The gateway's fee on a captured payment, GST included, in major units -- what the platform
     * actually received is the amount minus this. Zero when the gateway hasn't set one.
     */
    BigDecimal fee(String paymentId);
}
