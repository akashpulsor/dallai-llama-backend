package com.dalai.llama.pbx.core.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class KamailioOriginateRequest {
    private String callId;      // PBX-Core generates
    private String tenantId;
    private String agentContact;  // sip:1001@domain
    private String from;          // E164
    private String to;            // E164
}
