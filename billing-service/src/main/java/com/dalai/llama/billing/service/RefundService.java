package com.dalai.llama.billing.service;

import java.math.BigDecimal;
import java.util.UUID;

public interface RefundService {

    /**
     * Initiates a gateway refund for a captured payment.
     * Wallet debit happens automatically via the refund.created webhook.
     *
     * @param paymentId internal payment UUID
     * @param amount    refund amount (null = full refund)
     * @param reason    human-readable reason
     * @param trigger   who triggered it (e.g. "SYSTEM", "ADMIN", "SUBSCRIPTION_FAILED")
     * @return gateway refund ID
     * @throws IllegalArgumentException  if payment not found
     * @throws IllegalStateException     if payment is not in a refundable state
     */
    String initiateRefund(UUID paymentId, BigDecimal amount, String reason, String trigger);
}
