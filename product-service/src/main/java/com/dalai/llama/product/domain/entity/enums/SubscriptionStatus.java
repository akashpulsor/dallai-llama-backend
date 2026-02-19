package com.dalai.llama.product.domain.entity.enums;

/**
 * Subscription lifecycle states.
 *
 * Flow:
 * PENDING_PAYMENT → PENDING_PROVISION → ACTIVE → SUSPENDED/CANCELLED/EXPIRED
 */
public enum SubscriptionStatus {

    /**
     * Subscription created, waiting for payment/wallet funding
     */
    PENDING_PAYMENT,

    /**
     * Payment received, provisioning DID/SIP/Channels
     */
    PENDING_PROVISION,

    /**
     * Fully provisioned and operational
     */
    ACTIVE,

    /**
     * Suspended due to billing issue (can be reactivated)
     */
    SUSPENDED,

    /**
     * User cancelled (terminal state)
     */
    CANCELLED,

    /**
     * Billing period expired, not renewed (terminal state)
     */
    EXPIRED
}