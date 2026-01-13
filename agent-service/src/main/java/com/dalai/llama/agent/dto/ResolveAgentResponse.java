package com.dalai.llama.agent.dto;

import lombok.Builder;
import lombok.Data;

@Data @Builder
public class ResolveAgentResponse {
    private String agentId;
    private String contact;
}
