package com.dalai.llama.tenant.dto.response;

import lombok.Builder;

import java.util.List;
import java.util.UUID;

/**
 * Complete product configuration for a subscription.
 * Returned by GET /api/v1/internal/subscriptions/{id}/config
 *
 * This is the SINGLE SOURCE OF TRUTH for tenant-service.
 * Contains everything needed to provision a tenant app.
 */
@Builder
public record ProductConfigResponse(

        // Subscription context
        UUID subscriptionId,
        UUID tenantId,

        // Product & Plan
        ProductInfo product,
        PlanInfo plan,

        // All entitlements (feature flags)
        PlanEntitlementResponse entitlements,

        // AI configuration
        AiConfigResponse aiConfig,

        // All UI apps for this product
        List<ProductAppResponse> apps

) {}
