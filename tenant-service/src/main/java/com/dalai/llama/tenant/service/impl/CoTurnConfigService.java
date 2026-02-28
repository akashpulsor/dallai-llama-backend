package com.dalai.llama.tenant.service.impl;

import com.dalai.llama.tenant.domain.entity.TenantApp;
import com.dalai.llama.tenant.dto.request.TurnConfigRequest;
import com.dalai.llama.tenant.dto.response.TurnCredentialsResponse;
import com.dalai.llama.tenant.service.client.PbxCoreClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * CoTURN Configuration Service (Tenant Service side)
 *
 * Responsibility split:
 *   Tenant Service (here):
 *     - Plan tier → bandwidth limit mapping (business logic)
 *     - Dedicated vs shared TURN URL resolution (namespace topology)
 *     - Setting turnUrl on TenantApp entity
 *
 *   PBX-Core (remote):
 *     - HMAC-SHA1 credential generation (owns CoTURN shared secret)
 *     - Redis storage (turn:cred:{tenant})
 *     - Runtime credential serving: GET /api/v1/turn/credentials/{tenantSlug}
 *       (Agent UI calls this before WebRTC session to get fresh TURN creds)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CoTurnConfigService {

    private final PbxCoreClient pbxCoreClient;

    public void configureForSubscription(TenantApp app) {
        log.info("Configuring CoTURN via PBX-Core for subscription {}", app.getSubscriptionId());

        boolean dedicated = Boolean.TRUE.equals(app.getDedicatedInfrastructure());

        // ── Business logic stays here: plan tier → bandwidth ──
        String bandwidthLimit = getBandwidthLimit(app.getPlanTier());

        TurnConfigRequest request = TurnConfigRequest.builder()
                .tenantId(app.getTenant().getId())
                .subscriptionId(app.getSubscriptionId())
                .namespace(app.getNamespace())
                .dedicatedInfrastructure(dedicated)
                .maxBandwidthBps(bandwidthLimit)
                .build();

        // PBX-Core generates HMAC creds, stores in Redis, returns URLs
        TurnCredentialsResponse creds = pbxCoreClient.configureTurn(request);

        // Set resolved TURN URL on tenant app
        app.setTurnUrl(creds.getTurnUrl());

        log.info("CoTURN configured via PBX-Core for {} - turnUrl: {}",
                app.getNamespace(), creds.getTurnUrl());
    }

    /**
     * Plan tier → bandwidth limit. Business logic stays in tenant-service.
     * PBX-Core doesn't know what "ENTERPRISE" or "STANDARD" means.
     */
    private String getBandwidthLimit(String planTier) {
        return switch (planTier) {
            case "ENTERPRISE" -> "0";          // Unlimited
            case "PROFESSIONAL" -> "10000000"; // 10 Mbps
            case "STANDARD" -> "5000000";      // 5 Mbps
            default -> "2000000";              // 2 Mbps
        };
    }

    public void removeSubscriptionConfig(String tenantSlug) {
        log.info("Removing CoTURN config via PBX-Core for tenant {}", tenantSlug);
        pbxCoreClient.removeTurnConfig(tenantSlug);
    }
}