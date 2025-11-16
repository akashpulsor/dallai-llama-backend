package com.dalai.llama.pbx.core.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class OutboundCallRequest {
    @NotBlank private String callId;
    @NotBlank private String tenantId;
    @NotBlank private String agentId;
    @NotBlank private String to;
    @NotBlank private String from;
    private String cli;
}
