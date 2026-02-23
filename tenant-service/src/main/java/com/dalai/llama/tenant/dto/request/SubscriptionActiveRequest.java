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

        // ==================== PRODUCT & PLAN REFERENCES ====================
        UUID productId,
        String productCode,
        String productName,


        UUID planId,
        String planCode,
        String planName,
        String planTier,
        BigDecimal monthlyPrice,

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














