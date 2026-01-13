package com.dalai.llama.pbx.core.dto;

import lombok.Data;

@Data
public class AgentDto {

    private String agentId;
    private String agentName;
    private String extension;
    private String sipUser;
    private String sipPassword;

}
