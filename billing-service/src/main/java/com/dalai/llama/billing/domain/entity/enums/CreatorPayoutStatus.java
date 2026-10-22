package com.dalai.llama.billing.domain.entity.enums;

/**
 * Where one project's creator payout is. {@link #QUEUED} and {@link #AWAITING_ACCOUNT} are worked
 * out again on every dispatcher run; from {@link #READY} on, the amount is fixed and already taken
 * out of the creator's wallet, so a payout that ends {@link #FAILED} gives it back.
 */
public enum CreatorPayoutStatus {
    /** Opened by the client's final payment; amount not worked out yet. */
    QUEUED,
    /** Something to pay, but the creator hasn't added a bank account or UPI ID yet. */
    AWAITING_ACCOUNT,
    /** Production, platform charges and fees ate the whole amount -- nothing to send. */
    NOTHING_TO_PAY,
    /** Wallet debited, payout not yet accepted by RazorpayX (retried until it is). */
    READY,
    /** RazorpayX accepted the payout; waiting for its webhook. */
    SENT,
    PAID,
    /** RazorpayX rejected or reversed it; the wallet has the amount back. Ops can retry. */
    FAILED
}
