package com.dalai.llama.tenant.dto.request;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class EntitlementDto {
    private Integer maxPstnChannels;
    private Integer maxAgents;
    private Integer maxQueues;
    private Integer maxRingGroups;
    private Boolean aiBotEnabled;
    private Boolean aiSttEnabled;
    private Boolean aiTtsEnabled;
    private Boolean aiRoutingEnabled;
    private Boolean aiSentimentEnabled;
    private Boolean aiNoiseCancellationEnabled;
    private Boolean aiVoiceMorphEnabled;
    private Boolean aiAgentAssistEnabled;
    private Boolean recordingEnabled;
    private Boolean voicemailEnabled;
    private Boolean voicemailTranscriptionEnabled;
    private Boolean bargeEnabled;
    private Boolean whisperEnabled;
    private Boolean listenEnabled;
    private Boolean amdEnabled;
    private Boolean dncManagementEnabled;
    private Boolean ivrMultiLanguageEnabled;
    private Long aiTokensPerMonth;
    private java.math.BigDecimal aiRatePerMinute;
    private java.math.BigDecimal ratePerMinuteInbound;
    private java.math.BigDecimal ratePerMinuteOutbound;

    public static EntitlementDto from(com.dalai.llama.tenant.dto.response.PlanEntitlementResponse r) {
        return EntitlementDto.builder()
                .maxPstnChannels(r.maxPstnChannels())
                .maxAgents(r.maxAgents())
                .maxQueues(r.maxQueues())
                .maxRingGroups(r.maxRingGroups())
                .aiBotEnabled(r.aiBotEnabled())
                .aiSttEnabled(r.aiSttEnabled())
                .aiTtsEnabled(r.aiTtsEnabled())
                .aiRoutingEnabled(r.aiRoutingEnabled())
                .aiSentimentEnabled(r.aiSentimentEnabled())
                .aiNoiseCancellationEnabled(r.aiNoiseCancellationEnabled())
                .aiVoiceMorphEnabled(r.aiVoiceMorphEnabled())
                .aiAgentAssistEnabled(r.aiAgentAssistEnabled())
                .recordingEnabled(r.recordingEnabled())
                .voicemailEnabled(r.voicemailEnabled())
                .voicemailTranscriptionEnabled(r.voicemailTranscriptionEnabled())
                .bargeEnabled(r.bargeEnabled())
                .whisperEnabled(r.whisperEnabled())
                .listenEnabled(r.listenEnabled())
                .amdEnabled(r.amdEnabled())
                .dncManagementEnabled(r.dncManagementEnabled())
                .ivrMultiLanguageEnabled(r.ivrMultiLanguageEnabled())
                .aiTokensPerMonth(r.aiTokensPerMonth())
                .aiRatePerMinute(r.aiRatePerMinute())
                .ratePerMinuteInbound(r.ratePerMinuteInbound())
                .ratePerMinuteOutbound(r.ratePerMinuteOutbound())
                .build();
    }
}
