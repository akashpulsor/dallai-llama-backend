package com.dalai.llama.agent.dto;

import lombok.Data;

@Data
public class AgentDto {
    private Long id;
    private String externalId;
    private String username;
    private String displayName;
    private String email;
    private String tenantId;
    private boolean online;
    private boolean available;
}