package com.dalai.llama.agent.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class OutboundCallRequest {

    @NotBlank
    private String tenantId;
    @NotBlank private String agentId;
    @NotBlank private String agentUsername;

    @NotBlank private String agentContact; // from redis: sip:1001@tenant.com

    @NotBlank private String from; // CLI
    @NotBlank private String to;   // lead/customer phone

    @NotBlank private String sdpOffer; // WebRTC offer

    @NotBlank private String kamailioRpcUrl; // env variable
}
