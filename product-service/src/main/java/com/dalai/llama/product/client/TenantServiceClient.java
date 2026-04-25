package com.dalai.llama.product.client;

import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class TenantServiceClient {

    private final WebClient.Builder webClientBuilder;

    @Value("${services.tenant.url:http://tenant-service:8080}")
    private String tenantServiceUrl;

    private WebClient client() {
        return webClientBuilder.baseUrl(tenantServiceUrl).build();

    }

    /**
     * Notify tenant-service that subscription is active with FULL data.
     * Tenant-service will:
     * 1. Save to TenantSubscription table
     * 2. Create TenantApp records
     * 3. Configure Kamailio (SIP routing, subscriber)
     * 4. Configure FreePBX (dialplans, trunks)
     * 5. Setup Keycloak (if first subscription)
     */
    public SubscriptionActiveResponse notifySubscriptionActive(SubscriptionData data) {
        try {
            SubscriptionActiveResponse response = client().post()
                    .uri("/api/v1/internal/tenants/{tenantId}/activate", data.tenantId())
                    .bodyValue(data)
                    .retrieve()
                    .bodyToMono(SubscriptionActiveResponse.class)
                    .block();

            log.info("Notified tenant {} of subscription {} active - product: {}, did: {}",
                    data.tenantId(), data.subscriptionId(), data.productCode(), data.did().number());
            return response;

        } catch (Exception e) {
            log.error("Failed to notify tenant {}: {}", data.tenantId(), e.getMessage());
            throw new RuntimeException("Failed to notify tenant service", e);
        }
    }

    public TenantInfo getTenant(UUID tenantId) {
        try {
            return client().get()
                    .uri("/api/v1/internal/tenants/{tenantId}", tenantId)
                    .headers(h -> log.info(">>> OUTGOING HEADERS to tenant-service: {}", h))
                    .retrieve()
                    .bodyToMono(TenantInfo.class)
                    .block();
        } catch (Exception e) {
            log.error("Failed to get tenant {}: {}", tenantId, e.getMessage());
            throw new RuntimeException("Failed to get tenant", e);
        }
    }

    // ==================== FULL SUBSCRIPTION DATA ====================

    @Builder
    public record SubscriptionData(
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

    /**
     * DID details for dialplan configuration
     */
    @Builder
    public record DidData(
            UUID id,
            String number,           // +919876543210
            String displayNumber,    // +91 98765 43210
            String country,          // IN
            String region,           // Maharashtra
            String city,             // Mumbai
            String status,           // PENDING, ACTIVE
            BigDecimal monthlyRental
    ) {}

    /**
     * SIP Endpoint for Kamailio subscriber table
     * Used for inbound DID registration
     */
    @Builder
    public record SipEndpointData(
            UUID id,
            String username,         // did_919876543210
            String passwordHash,     // HA1 hash for Kamailio
            String domain,           // sip.dalaillama.in
            String realm             // dalaillama.in
    ) {}

    /**
     * Channel allocation for rate limiting
     */
    @Builder
    public record ChannelData(
            UUID id,
            String direction,        // INBOUND, OUTBOUND, BOTH
            int total,
            Integer inbound,
            Integer outbound
    ) {}

    /**
     * Tenant's SIP trunk credentials (customer's access to YOUR platform)
     * For Kamailio subscriber table
     */
    @Builder
    public record TenantSipTrunkData(
            UUID id,
            String username,         // tenant_acme_trunk
            String passwordHash,     // HA1 hash for Kamailio
            String passwordPlain,    // Plain password (shown once)
            String domain,           // sip.dalaillama.in
            int port,                // 5060
            String realm,            // dalaillama.in
            String transport,        // UDP/TCP/TLS
            int maxConcurrentCalls
    ) {}

    /**
     * Platform SIP trunk (Epsilon) for outbound calls
     * For FreePBX trunk configuration
     */
    @Builder
    public record PlatformTrunkData(
            UUID id,
            String provider,         // EPSILON
            String server,           // sip.epsilon.in
            int port,                // 5060
            String transport,        // UDP
            List<String> codecs      // G711, G729, OPUS
    ) {}

    /**
     * Product app template
     */
    @Builder
    public record ProductAppInfo(
            String appType,          // AGENT_DASHBOARD, SUPERVISOR_DASHBOARD
            String displayName,      // Agent Dashboard
            String subdomain,        // agent
            String icon,             // headset
            int displayOrder
    ) {}

    // ==================== RESPONSE ====================

    public record SubscriptionActiveResponse(
            UUID tenantId,
            UUID subscriptionId,
            UUID tenantAppId,
            String status,
            List<AppInfo> apps,
            AdminCredentials adminCredentials,
            ConfigStatus configStatus
    ) {}

    public record AppInfo(
            UUID id,
            String appType,
            String displayName,
            String url,
            String icon
    ) {}

    public record AdminCredentials(
            String email,
            String temporaryPassword,
            String loginUrl
    ) {}

    /**
     * Status of Kamailio/FreePBX configuration
     */
    public record ConfigStatus(
            boolean kamailioConfigured,
            boolean freepbxConfigured,
            String message
    ) {}

    public record TenantInfo(
            UUID id,
            String name,
            String slug,
            String status,
            UUID adminUserId,
            String adminUserEmail
    ) {}
}