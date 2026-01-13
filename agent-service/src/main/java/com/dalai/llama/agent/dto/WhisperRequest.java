package com.dalai.llama.agent.dto;

import lombok.Data;

@Data
public class WhisperRequest {
    private Long supervisorId;
    private String supervisorContact;
}
