package com.dalai.llama.tenant.dto.request;


import lombok.Builder;
import lombok.Data;
import java.util.UUID;

@Data
@Builder
public class RtpEngineConfigRequest {
    private UUID tenantId;
    private UUID subscriptionId;
    private String namespace;
    private Boolean dedicatedInfrastructure;

    // Resolved codec preference
    private String codecs;             // "opus,G722,PCMU,PCMA"

    // Recording
    private Boolean recordingEnabled;
    private String recordingPath;      // /var/spool/rtpengine/tenant-acme

    // Limits
    private Integer maxChannels;
    private Boolean transcodingEnabled;

    // AI audio fork (for real-time STT)
    private Boolean aiForkEnabled;
    private String aiForkTarget;       // udp:ai-service.telecom.svc:5555
}