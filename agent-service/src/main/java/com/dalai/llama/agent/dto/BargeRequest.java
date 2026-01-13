package com.dalai.llama.agent.dto;

import lombok.Data;

@Data
public class BargeRequest {
    private Long supervisorId;
    private String supervisorContact; // sip:XXXX or webrtc:XXXX
    private boolean muteSupervisor = false;
}
