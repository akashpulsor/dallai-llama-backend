package com.dalai.llama.tenant.dto.request;

import lombok.Builder;
import lombok.Data;
import java.util.UUID;

@Data
@Builder
public class TenantProvisioningRequest {
    private UUID tenantId;
    private UUID subscriptionId;
    private String productCode;
    private String planCode;
    private String planTier;
    private String namespace;
    private Boolean dedicatedInfrastructure;

    // SIP
    private String sipEndpointUsername;
    private String sipEndpointPasswordHash;
    private String sipEndpointDomain;
    private String tenantTrunkUsername;
    private String tenantTrunkPasswordHash;
    private String tenantTrunkDomain;

    // DID
    private String didNumber;

    // Channels
    private Integer channelTotal;
    private Integer channelInbound;
    private Integer channelOutbound;
    private String channelDirection;

    // Entitlements
    private EntitlementDto entitlements;
}