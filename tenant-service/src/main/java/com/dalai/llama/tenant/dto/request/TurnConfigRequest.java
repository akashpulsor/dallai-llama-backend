package com.dalai.llama.tenant.dto.request;

import lombok.Builder;
import lombok.Data;

import java.util.UUID;

// TurnConfigRequest.java
@Data
@Builder
public class TurnConfigRequest {
    private UUID tenantId;
    private UUID subscriptionId;
    private String namespace;
    private Boolean dedicatedInfrastructure;
    private String maxBandwidthBps;
}