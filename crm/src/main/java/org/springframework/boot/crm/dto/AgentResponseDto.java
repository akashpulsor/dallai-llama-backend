package org.springframework.boot.crm.dto;

import lombok.Data;

@Data
public class AgentResponseDto {

    private int agentId;

    private int businessId;

    private String agentName;

    private String persona;

    private String role;

    private String voice;

    private boolean active;
}
