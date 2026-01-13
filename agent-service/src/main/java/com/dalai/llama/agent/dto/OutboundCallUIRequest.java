package com.dalai.llama.agent.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class OutboundCallUIRequest {
    @NotBlank
    private String to;   // customer's phone number

    private String from; // CLI (optional; if missing agentService chooses default DID)

    @NotBlank
    private String sdpOffer; // WebRTC SDP offer from browser

    private String leadId; // optional - fo
}
