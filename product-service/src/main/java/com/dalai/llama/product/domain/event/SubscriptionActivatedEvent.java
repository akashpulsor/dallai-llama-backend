package com.dalai.llama.product.domain.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubscriptionActivatedEvent {

    // ─── Subscription ────────────────────────────────
    private UUID subscriptionId;
    private UUID tenantId;
    private String status;
    private Instant activatedAt;
    private Instant expiresAt;

    // ─── Product & Plan ──────────────────────────────
    private UUID productId;
    private String productCode;
    private String productName;
    private UUID planId;
    private String planCode;
    private String planName;
    private String planTier;
    private BigDecimal monthlyPrice;
    private BigDecimal aiRatePerMin;

    // ─── Entitlements ────────────────────────────────
    private Integer agentSeats;
    private Integer maxAgents;
    private Integer maxDids;
    private Integer maxChannels;
    private Integer includedMinutes;

    // ─── DID ─────────────────────────────────────────
    private UUID didId;
    private String didNumber;
    private String didDisplayNumber;
    private String didCountry;
    private String didRegion;
    private String didCity;
    private BigDecimal didMonthlyRental;

    // ─── SIP Endpoint (inbound, for Kamailio registration) ───
    private UUID sipEndpointId;
    private String sipEndpointUsername;
    private String sipEndpointPasswordHash;
    private String sipEndpointDomain;
    private String sipEndpointRealm;

    // ─── Channels ────────────────────────────────────
    private UUID channelBundleId;
    private String channelDirection;
    private Integer totalChannels;
    private Integer inboundChannels;
    private Integer outboundChannels;

    // ─── Tenant SIP Trunk (customer's creds to your platform) ───
    private UUID tenantSipTrunkId;
    private String tenantSipTrunkUsername;
    private String tenantSipTrunkPasswordHash;
    private String tenantSipTrunkPasswordPlain;
    private String tenantSipTrunkDomain;
    private Integer tenantSipTrunkPort;
    private String tenantSipTrunkRealm;
    private String tenantSipTrunkTransport;
    private Integer tenantSipTrunkMaxConcurrentCalls;

    // ─── Platform SIP Trunk (Epsilon — for outbound) ───
    private UUID platformTrunkId;
    private String platformTrunkProvider;
    private String platformTrunkServer;
    private Integer platformTrunkPort;
    private String platformTrunkTransport;
    private List<String> platformTrunkCodecs;

    // ─── Apps ────────────────────────────────────────
    private List<ProductAppData> productApps;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProductAppData {
        private String appType;
        private String displayName;
        private String subdomain;
        private String icon;
        private Integer displayOrder;
    }
}