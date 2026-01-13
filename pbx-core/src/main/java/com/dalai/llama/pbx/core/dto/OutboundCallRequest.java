package com.dalai.llama.pbx.core.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class OutboundCallRequest {

    @NotBlank private String tenantId;
    @NotBlank private String callId;

    @NotBlank private String agentId;
    @NotBlank private String agentUsername;
    @NotBlank private String agentContact; // SIP URI: sip:1001@tenant.com

    @NotBlank private String from; // Caller-ID
    @NotBlank private String to;   // PSTN / SIP target

    @NotBlank private String sdpOffer;

    // NEW: Tenant-resolved Kamailio RPC URL
    @NotBlank private String kamailioRpcUrl;
}
