package com.dalai.llama.product.dto.creatorvideo;

import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

/**
 * The "/me"-style response: resolvable either by {@code subscriptionId} (the primary lookup key
 * per product decision) or by {@code tenantId} (creator-ui's initial-load convenience, resolves to
 * the tenant's current -- most recent -- creator-video subscription). {@code subscriptionId}/
 * {@code currentPeriodEnd} are null for a tenant that has never subscribed at all (free-by-default,
 * not an absence/error).
 *
 * <p>Deliberately does not carry name/email/profile fields -- product-service doesn't own tenant
 * identity, and creator-ui already has the tenant's own profile from tenant-service's {@code
 * GET /tenants/me}; duplicating it here would be a second source of truth for the same data.
 */
@Builder
public record CreatorVideoEntitlementsResponse(
        UUID tenantId,
        UUID subscriptionId,
        String planCode,
        String planName,
        String status,
        Instant currentPeriodEnd,
        CreatorVideoEntitlements entitlements
) {
}
