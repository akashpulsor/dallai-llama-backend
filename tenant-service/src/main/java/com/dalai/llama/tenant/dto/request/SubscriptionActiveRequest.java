package com.dalai.llama.tenant.dto.request;

import lombok.Builder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Builder
public record SubscriptionActiveRequest(
        // Subscription identifiers
        UUID subscriptionId,
        UUID tenantId,
        String status,
        Instant subscribedAt,
        Instant activatedAt,
        Instant expiresAt,

        // Product & Plan
        String productCode,
        String productName,
        String planCode,
        String planName,
        String planTier,

        // Entitlements
        int agentSeats,
        int maxAgents,
        int maxDids,
        int maxChannels,
        int includedMinutes,
        BigDecimal aiRatePerMin,

        // Resources
        DidData did,
        SipEndpointData sipEndpoint,
        ChannelData channels,
        TenantSipTrunkData tenantSipTrunk,
        PlatformTrunkData platformTrunk,

        // Apps to create
        List<ProductAppInfo> productApps
) {}














