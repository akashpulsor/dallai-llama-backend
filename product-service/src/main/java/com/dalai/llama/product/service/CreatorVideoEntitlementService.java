package com.dalai.llama.product.service;

import com.dalai.llama.product.dto.creatorvideo.CreatorVideoEntitlementsResponse;

import java.util.UUID;

public interface CreatorVideoEntitlementService {

    /** Primary lookup, per product decision -- resolves the entitlements attached to this exact
     * subscription's plan, but the STATUS check still applies (a CANCELLED/PAST_DUE/PAUSED
     * subscription's entitlements resolve to the free tier regardless of which plan it points at). */
    CreatorVideoEntitlementsResponse getEntitlementsBySubscription(UUID subscriptionId);

    /** Convenience for a caller that only has tenantId (e.g. creator-ui's initial page load) --
     * resolves the tenant's current (most recent) creator-video subscription, or the free tier if
     * none exists yet. */
    CreatorVideoEntitlementsResponse getEntitlementsByTenant(UUID tenantId);

    void invalidateCache(UUID tenantId, UUID subscriptionId);
}
