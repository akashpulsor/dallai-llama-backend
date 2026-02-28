package com.dalai.llama.tenant.service.impl;

import com.dalai.llama.tenant.domain.entity.TenantApp;
import com.dalai.llama.tenant.dto.request.KamailioProvisioningRequest;
import com.dalai.llama.tenant.dto.response.PlanEntitlementResponse;
import com.dalai.llama.tenant.dto.response.TenantTelecomEndpoints;
import com.dalai.llama.tenant.service.client.PbxCoreClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Kamailio Configuration Service (Tenant Service side)
 *
 * Responsibility split:
 *   Tenant Service (here):
 *     - Product → routing target mapping (AI_CC → ai_contact_center, etc.)
 *     - Entitlement → channel limits resolution
 *     - Dedicated vs shared dispatcher set ID logic
 *     - Building the complete provisioning request with all resolved values
 *
 *   PBX-Core (remote):
 *     - subscriber table INSERT/UPDATE (owns Kamailio DB)
 *     - domain table INSERT
 *     - dialplan table INSERT
 *     - dispatcher table INSERT
 *     - Redis: channel limits, subscription lookup
 *     - kamcmd domain.reload + dispatcher.reload (hot reload)
 *     - Returns computed SIP/WSS/TURN endpoints
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KamailioConfigService {

    private final PbxCoreClient pbxCoreClient;

    public String configureForSubscription(TenantApp app, PlanEntitlementResponse entitlements) {
        log.info("Configuring Kamailio via PBX-Core for subscription {} - product: {}",
                app.getSubscriptionId(), app.getProductCode());

        // ── Product → routing target mapping (business logic stays here) ──
        String routingTarget = resolveRoutingTarget(app.getProductCode());

        // ── Dispatcher set ID logic (business logic: dedicated vs shared) ──
        int dispatcherSetId;
        if (Boolean.TRUE.equals(app.getDedicatedInfrastructure())) {
            dispatcherSetId = 100 + Math.abs(app.getSubscriptionId().hashCode() % 900);
        } else {
            dispatcherSetId = 1;
        }

        // ── Channel limit resolution (business logic: entitlement → limits) ──
        int maxChannels = entitlements.maxPstnChannels();
        int maxInbound = app.getChannelInbound() != null ? app.getChannelInbound() : maxChannels;
        int maxOutbound = app.getChannelOutbound() != null ? app.getChannelOutbound() : maxChannels;

        KamailioProvisioningRequest request = KamailioProvisioningRequest.builder()
                .tenantId(app.getTenant().getId())
                .subscriptionId(app.getSubscriptionId())
                .productCode(app.getProductCode())
                .namespace(app.getNamespace())
                .dedicatedInfrastructure(app.getDedicatedInfrastructure())
                // SIP Endpoints
                .sipEndpointUsername(app.getSipEndpointUsername())
                .sipEndpointPasswordHash(app.getSipEndpointPasswordHash())
                .sipEndpointDomain(app.getSipEndpointDomain())
                .tenantTrunkUsername(app.getTenantTrunkUsername())
                .tenantTrunkPasswordHash(app.getTenantTrunkPasswordHash())
                .tenantTrunkDomain(app.getTenantTrunkDomain())
                // DID
                .didNumber(app.getDidNumber())
                // Resolved routing target (product logic applied here)
                .routingTarget(routingTarget)
                // Resolved dispatcher
                .dispatcherSetId(dispatcherSetId)
                // Resolved channel limits
                .maxChannels(maxChannels)
                .maxInbound(maxInbound)
                .maxOutbound(maxOutbound)
                .channelDirection(app.getChannelDirection() != null ? app.getChannelDirection() : "BOTH")
                // Feature flags (resolved from entitlements)
                .aiEnabled(entitlements.aiBotEnabled())
                .recordingEnabled(entitlements.recordingEnabled())
                // Rates
                .rateInbound(entitlements.ratePerMinuteInbound() != null
                        ? entitlements.ratePerMinuteInbound().toString() : "1.5")
                .rateOutbound(entitlements.ratePerMinuteOutbound() != null
                        ? entitlements.ratePerMinuteOutbound().toString() : "1.5")
                .build();

        // PBX-Core does: DB inserts + Redis channel limits + kamcmd reload
        TenantTelecomEndpoints endpoints = pbxCoreClient.provisionKamailio(request);

        // Set computed endpoints back on app entity
        app.setSipUdpUrl(endpoints.getSipUdpUrl());
        app.setSipTlsUrl(endpoints.getSipTlsUrl());
        //app.setSipWssUrl(endpoints.getSipWssUrl());
        app.setFreeswitchEslHost(endpoints.getEslHost());
        app.setFreeswitchEslPort(endpoints.getEslPort());

        log.info("Kamailio configured via PBX-Core for {} - sipWss: {}, eslHost: {}",
                app.getNamespace(), endpoints.getSipWssUrl(), endpoints.getEslHost());

        return endpoints.getConfigSummary();
    }

    /**
     * Product → routing target. Business logic stays in tenant-service.
     * PBX-Core stores "ai_contact_center" as a string, doesn't interpret it.
     */
    private String resolveRoutingTarget(String productCode) {
        return switch (productCode) {
            case "AI_CC" -> "ai_contact_center";
            case "CONV_IVR" -> "conversational_ivr";
            case "BASIC_PBX" -> "basic_pbx";
            case "OUTBOUND_DIALER" -> "dialer_inbound";
            case "VIRTUAL_RECEPTIONIST" -> "virtual_receptionist";
            default -> "default_ivr";
        };
    }

    public void removeSubscriptionConfig(UUID subscriptionId) {
        log.info("Removing Kamailio config via PBX-Core for subscription {}", subscriptionId);
        pbxCoreClient.deprovisionKamailio(subscriptionId);
    }
}