package org.springframework.boot.crm.dto;

import jakarta.persistence.Column;
import lombok.Data;

@Data
public class AgentRequestDto {

    private int businessId;

    private int agentId;

    private String agentName;

    private String persona;

    private String role;

    private String voice;
}
