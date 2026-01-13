package com.dalai.llama.agent.dto;

import lombok.Data;

@Data
public class MonitorRequest {
    private Long supervisorId;
    private String supervisorContact;
    private boolean record = false;
}
