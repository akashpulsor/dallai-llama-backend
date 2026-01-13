package com.dalai.llama.agent.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class AgentConfigurationRequest {
    private String extension;
    private String displayName;
    private String email;
    private String phoneNumber;
    private Integer maxConcurrentCalls;
    private List<String> skills;
    private List<String> queueMemberships;
    private Map<String, Object> preferences;
    private String teamId;
}
