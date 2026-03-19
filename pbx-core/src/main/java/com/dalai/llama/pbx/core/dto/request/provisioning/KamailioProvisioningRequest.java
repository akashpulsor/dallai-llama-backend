package com.dalai.llama.pbx.core.dto.request.provisioning;


import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Received from tenant-service KamailioConfigService.configureForSubscription().
 *
 * Tenant-service has already resolved:
 *   - routingTarget (product code → QUEUE:default, AI_BOT:default, etc.)
 *   - dispatcherSetId (dedicated=100+hash, shared=1)
 *   - channel limits (from entitlements)
 *   - rates (from plan)
 *
 * PBX-Core must:
 *   1. INSERT subscriber (SIP endpoint + trunk)
 *   2. INSERT domain
 *   3. INSERT/UPDATE dispatcher
 *   4. INSERT dialplan rules (number manipulation)
 *   5. SET channel limits in Redis
 *   6. SET DID→tenantId mapping in Redis
 *   7. kamcmd domain.reload + dispatcher.reload + dialplan.reload
 *   8. Return TenantTelecomEndpoints with computed SIP URLs
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KamailioProvisioningRequest {

    private UUID tenantId;
    private UUID subscriptionId;
    private String productCode;
    private String namespace;
    private Boolean dedicatedInfrastructure;

    // ── SIP Endpoint (inbound DID registration) ──
    private String sipEndpointUsername;
    private String sipEndpointPasswordHash;
    private String sipEndpointDomain;

    // ── Tenant SIP Trunk (customer credentials to platform) ──
    private String tenantTrunkUsername;
    private String tenantTrunkPasswordHash;
    private String tenantTrunkDomain;

    // ── DID ──
    private String didNumber;

    // ── Routing (resolved by tenant-service from product code) ──
    private String routingTarget;
    private Integer dispatcherSetId;

    // ── Channel limits (resolved from entitlements) ──
    private Integer maxChannels;
    private Integer maxInbound;
    private Integer maxOutbound;
    private String channelDirection;

    // ── Feature flags ──
    private Boolean aiEnabled;
    private Boolean recordingEnabled;

    // ── Rates ──
    private String rateInbound;
    private String rateOutbound;
}