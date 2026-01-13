package com.dalai.llama.agent.dto;

import lombok.Data;

@Data
public class ResolveAgentRequest {
    private String agentId;
    private String tenantId;
    private String callId;
}
