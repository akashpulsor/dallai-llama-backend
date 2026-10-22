package com.dalai.llama.billing.service.payment;

import com.dalai.llama.billing.domain.entity.enums.PayoutMethod;

import java.math.BigDecimal;
import java.util.UUID;

/** Money out to a creator. Implemented by {@link RazorpayXPayoutGateway}. */
public interface PayoutGateway {

    /** A RazorpayX contact for this creator -- the person a fund account belongs to. */
    String createContact(UUID tenantId, String name);

    /** A bank account or UPI ID under {@code contactId}; returns the fund account id. */
    String createFundAccount(String contactId, PayoutMethod method, String holderName, String ifsc,
                             String accountNumber, String vpa);

    /** Sends {@code amount} (INR) to the fund account. {@code referenceId} doubles as the
     * idempotency key, so a retry after a lost response returns the same payout, never a second one.
     *
     * @throws PayoutRejectedException when RazorpayX refuses the request outright (nothing was
     *         sent); any other exception may be retried with the same {@code referenceId} */
    PayoutResult createPayout(String fundAccountId, PayoutMethod method, BigDecimal amount, String referenceId);

    record PayoutResult(String id, String status, String utr) {}

    class PayoutRejectedException extends RuntimeException {
        public PayoutRejectedException(String message) {
            super(message);
        }
    }
}
