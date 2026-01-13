package com.dalai.llama.agent.dto;
import lombok.Builder;
import lombok.Data;
@Data @Builder
public class UnassignAgentRequest {
    private String tenantId;
    private String agentId;
}