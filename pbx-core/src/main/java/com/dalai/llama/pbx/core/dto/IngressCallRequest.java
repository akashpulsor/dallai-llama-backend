package com.dalai.llama.pbx.core.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class IngressCallRequest {
    @NotBlank private String callId;
    @NotBlank private String tenantId;
    @NotBlank private String from;
    @NotBlank private String to;
    @NotBlank private String entrypoint; // e.g., team:support
    private boolean webrtc;
}
