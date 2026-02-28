package com.dalai.llama.tenant.dto.request;

import lombok.Builder;
import lombok.Data;

import java.util.UUID;

@Data
@Builder
public class FreeSwitchConfigRequest {
    private UUID tenantId;
    private UUID subscriptionId;
    private String productCode;
    private String namespace;
    private Boolean dedicatedInfrastructure;
    private Boolean aiEnabled;
    private Boolean recordingEnabled;
    private Boolean voicemailEnabled;
    private Boolean voicemailTranscriptionEnabled;
    private Boolean bargeEnabled;
    private Boolean whisperEnabled;
    private Boolean listenEnabled;
    private Boolean amdEnabled;
    private Boolean dncEnabled;
    private Boolean multiLanguageEnabled;
    private Integer maxAgents;
    private Integer maxQueues;
    private Integer maxRingGroups;
}
