package com.dalai.llama.product.dto.response;


import lombok.*;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EntitlementResponse {

    // Agents & channels
    private int maxAgents;
    private int maxSupervisors;
    private int maxConcurrentLogins;
    private int maxPstnChannels;
    private int maxDids;

    // Voice features
    private boolean inboundEnabled;
    private boolean outboundEnabled;
    private boolean recordingEnabled;

    // Analytics
    private boolean analyticsEnabled;
    private int analyticsRetentionDays;

    // AI features
    private boolean aiSttEnabled;
    private boolean aiLlmEnabled;
    private boolean aiBotEnabled;
    private boolean aiSentimentEnabled;
    private long aiTokensPerMonth;

    // Queue / IVR
    private int maxQueues;
    private int maxIvrFlows;

    // Storage
    private int recordingStorageGb;
    private int recordingRetentionDays;

    // Supervisor
    private boolean bargeEnabled;
    private boolean whisperEnabled;
    private boolean monitorEnabled;

    // Misc
    private boolean conferenceEnabled;
    private boolean voicemailEnabled;
    private boolean callbackEnabled;
}
