package com.dalai.llama.agent.dto;

import lombok.Data;

@Data
public class JoinConferenceRequest {
    private String participantContact;
    private String role;
}
