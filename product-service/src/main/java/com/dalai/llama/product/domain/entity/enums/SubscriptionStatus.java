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


    FAILED,
    /**
     * Billing period expired, not renewed (terminal state)
     */
    EXPIRED,

    PROVISIONING_FAILED,

    /**
     * User-initiated hold: billing stops, entitlements drop to the product's free tier, resumable
     * without re-subscribing. Distinct from SUSPENDED (a billing-issue state the platform imposes)
     * and CANCELLED (terminal). Only meaningful for products with no physical resources to release
     * on pause (e.g. creator-video) -- PBX products keep provisioned DIDs/trunks either way, so
     * this status simply isn't reached on that saga path.
     */
    PAUSED,

    /**
     * A recurring renewal charge failed (insufficient wallet balance). Entitlements drop to the
     * free tier immediately -- no grace period -- but the subscription is not cancelled: the
     * scheduler keeps retrying the same recurring charge daily, and a later successful retry
     * flips this back to ACTIVE automatically once the tenant tops up.
     */
    PAST_DUE
}