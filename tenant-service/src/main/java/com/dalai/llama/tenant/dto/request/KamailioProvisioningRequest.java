package com.dalai.llama.tenant.dto.request;

import lombok.Builder;
import lombok.Data;
import java.util.UUID;

@Data
@Builder
public class KamailioProvisioningRequest {
    private UUID tenantId;
    private UUID subscriptionId;
    private String productCode;
    private String namespace;
    private Boolean dedicatedInfrastructure;

    // ── SIP Endpoints ──
    private String sipEndpointUsername;
    private String sipEndpointPasswordHash;
    private String sipEndpointDomain;
    private String tenantTrunkUsername;
    private String tenantTrunkPasswordHash;
    private String tenantTrunkDomain;

    // ── DID ──
    private String didNumber;

    // ── Resolved routing target (product logic already applied) ──
    private String routingTarget;    // ai_contact_center, conversational_ivr, basic_pbx, etc.

    // ── Resolved dispatcher ──
    private Integer dispatcherSetId; // 1 for shared, 100-999 for dedicated

    // ── Resolved channel limits ──
    private Integer maxChannels;
    private Integer maxInbound;
    private Integer maxOutbound;
    private String channelDirection;  // BOTH, INBOUND, OUTBOUND

    // ── Feature flags (resolved from entitlements) ──
    private Boolean aiEnabled;
    private Boolean recordingEnabled;

    // ── Rates ──
    private String rateInbound;
    private String rateOutbound;
}