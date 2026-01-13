package com.dalai.llama.agent.dto;

import lombok.Builder;
import lombok.Data;

@Data @Builder
public class AllocateAgentResponse {
    private String agentId;
    private String contact;   // sip:... or webrtc:...
    private String reason;    // optional diagnostic
}
