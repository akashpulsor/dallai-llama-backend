package com.dalai.llama.agent.dto;


import lombok.Data;

@Data
public class AllocateAgentRequest {
    private String tenantId;
    private String entrypoint;
    private String callId;
}

