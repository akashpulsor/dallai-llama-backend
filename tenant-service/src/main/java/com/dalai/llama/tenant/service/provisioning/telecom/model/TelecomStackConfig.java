package com.dalai.llama.tenant.service.provisioning.telecom.model;

import lombok.Builder;
import lombok.Data;
import java.util.List;

/**
 * Comprehensive configuration for the complete telecom stack.
 * Supports all modern CC features: conference, barge, whisper, screen pop, etc.
 */
@Data
@Builder
public class TelecomStackConfig {
    
    // ============================================================
    // TENANT IDENTIFICATION
    // ============================================================
    private String tenantId;
    private String tenantSlug;
    private String namespace;
    private String realm;
    private boolean isDedicated;
    
    // ============================================================
    // SERVICE URLS
    // ============================================================
    private String pbxCoreUrl;
    private String agentServiceUrl;
    private String aiServiceUrl;
    private String webhookUrl;
    
    // ============================================================
    // KAFKA CONFIGURATION
    // ============================================================
    private String kafkaBootstrap;
    private String kafkaTopicPrefix;
    
    // ============================================================
    // REDIS CONFIGURATION
    // ============================================================
    private String redisUrl;
    private String redisPassword;
    
    // ============================================================
    // WEBRTC CONFIGURATION
    // ============================================================
    private boolean enableWebRtc;
    private int wssPort;
    private int turnPort;
    private int turnTlsPort;
    private String turnSecret;
    
    // ============================================================
    // MEDIA PORTS
    // ============================================================
    private int rtpPortRangeStart;
    private int rtpPortRangeEnd;
    
    // ============================================================
    // SIP PORTS
    // ============================================================
    private int sipUdpPort;
    private int sipTcpPort;
    private int sipTlsPort;
    
    // ============================================================
    // FREESWITCH ESL
    // ============================================================
    private int eslPort;
    private String eslPassword;
    
    // ============================================================
    // CAPACITY LIMITS
    // ============================================================
    private int maxAgents;
    private int maxConcurrentCalls;
    private int maxQueues;
    private int maxIvrFlows;
    private int maxDids;
    
    // ============================================================
    // RECORDING CONFIGURATION
    // ============================================================
    private boolean enableRecording;
    private String recordingPath;
    private String recordingFormat;
    private boolean recordingStereo;
    private boolean recordingPciPause;
    private int recordingRetentionDays;
    
    // ============================================================
    // AI FEATURES
    // ============================================================
    private boolean enableAiTranscription;
    private boolean enableAiRouting;
    private boolean enableAiNoiseCancellation;
    private boolean enableAiSentiment;
    private boolean enableAiAgentAssist;
    private boolean enableAcwAutomation;
    private int aiMinutesPerMonth;
    
    // ============================================================
    // CONFERENCE CONFIGURATION
    // ============================================================
    private boolean enableConference;
    private int maxConferenceRooms;
    private int maxConferenceParticipants;
    private boolean conferenceRecording;
    private boolean conferenceDialOut;
    private boolean conferenceModerator;
    private String conferenceDefaultProfile;
    
    // ============================================================
    // SUPERVISOR FEATURES (BARGE/WHISPER)
    // ============================================================
    private boolean enableSupervisorFeatures;
    private boolean enableSilentMonitor;
    private boolean enableWhisper;
    private boolean enableBarge;
    private boolean enableTakeover;
    private boolean enableCoach;
    
    // ============================================================
    // QUEUE CONFIGURATION
    // ============================================================
    private boolean enableQueues;
    private boolean enableSkillsRouting;
    private boolean enablePriorityRouting;
    private boolean enableCallbackQueue;
    private int queueMaxWaitTime;
    private int queueServiceLevel;
    private String queueOverflowAction;
    
    // ============================================================
    // OUTBOUND DIALER
    // ============================================================
    private boolean enableOutboundDialer;
    private boolean enablePredictiveDialing;
    private boolean enableProgressiveDialing;
    private boolean enablePreviewDialing;
    private int dialerMaxConcurrent;
    private int dialerPacingRatio;
    
    // ============================================================
    // IVR CONFIGURATION
    // ============================================================
    private boolean enableIvr;
    private boolean enableConversationalIvr;
    private boolean enableDtmfFallback;
    private String ivrDefaultLanguage;
    private List<String> ivrSupportedLanguages;
    
    // ============================================================
    // VOICEMAIL CONFIGURATION
    // ============================================================
    private boolean enableVoicemail;
    private int voicemailMaxDuration;
    private boolean voicemailTranscription;
    private boolean voicemailEmailNotify;
    
    // ============================================================
    // SCREEN POP & CTI
    // ============================================================
    private boolean enableScreenPop;
    private String crmIntegrationType;
    private String crmWebhookUrl;
    
    // ============================================================
    // OMNICHANNEL
    // ============================================================
    private boolean enableOmnichannel;
    private boolean enableWhatsApp;
    private boolean enableSms;
    private boolean enableEmail;
    private boolean enableWebChat;
    
    // ============================================================
    // COMPLIANCE
    // ============================================================
    private boolean enableDncList;
    private boolean enableCallDisposition;
    private boolean enableAgentScripts;
    private String complianceRegion;
    
    // ============================================================
    // TLS CERTIFICATES
    // ============================================================
    private String tlsCertPath;
    private String tlsKeyPath;
    private String tlsCaPath;
    
    // ============================================================
    // DERIVED HELPERS
    // ============================================================
    
    public String getKafkaRegistrationTopic() {
        return kafkaTopicPrefix + "-registration-events";
    }
    
    public String getKafkaCallTopic() {
        return kafkaTopicPrefix + "-call-events";
    }
    
    public String getKafkaAiResultsTopic() {
        return kafkaTopicPrefix + "-ai-results";
    }
    
    public String getKafkaRtpTopic() {
        return kafkaTopicPrefix + "-rtp-events";
    }
    
    public String getKafkaConferenceTopic() {
        return kafkaTopicPrefix + "-conference-events";
    }
    
    public String getKafkaSupervisorTopic() {
        return kafkaTopicPrefix + "-supervisor-events";
    }
    
    public String getKafkaQueueTopic() {
        return kafkaTopicPrefix + "-queue-events";
    }
    
    public String getRtpEngineSocket() {
        return "udp:rtpengine:22222";
    }
    
    public String getFreeSwitchInternalDomain() {
        return tenantSlug + ".internal";
    }
    
    public String getConferenceProfile() {
        return conferenceDefaultProfile != null ? conferenceDefaultProfile : "wideband";
    }
    
    public String getAiFeatureFlags() {
        StringBuilder flags = new StringBuilder();
        if (enableAiTranscription) flags.append("transcription,");
        if (enableAiRouting) flags.append("routing,");
        if (enableAiNoiseCancellation) flags.append("noise-cancel,");
        if (enableAiSentiment) flags.append("sentiment,");
        if (enableAiAgentAssist) flags.append("agent-assist,");
        if (enableAcwAutomation) flags.append("acw,");
        return flags.length() > 0 ? flags.substring(0, flags.length() - 1) : "none";
    }
    
    public String getSupervisorFeatureFlags() {
        StringBuilder flags = new StringBuilder();
        if (enableSilentMonitor) flags.append("monitor,");
        if (enableWhisper) flags.append("whisper,");
        if (enableBarge) flags.append("barge,");
        if (enableTakeover) flags.append("takeover,");
        if (enableCoach) flags.append("coach,");
        return flags.length() > 0 ? flags.substring(0, flags.length() - 1) : "none";
    }
    
    public static TelecomStackConfigBuilder defaultConfig(String tenantId, String tenantSlug) {
        return TelecomStackConfig.builder()
            .tenantId(tenantId)
            .tenantSlug(tenantSlug)
            .namespace("tenant-" + tenantSlug)
            .realm(tenantSlug + ".dalaillama.in")
            .isDedicated(false)
            .sipUdpPort(5060).sipTcpPort(5060).sipTlsPort(5061)
            .wssPort(8443).turnPort(3478).turnTlsPort(5349).eslPort(8021)
            .rtpPortRangeStart(30000).rtpPortRangeEnd(40000)
            .enableWebRtc(true)
            .enableRecording(true).recordingPath("/recordings").recordingFormat("wav")
            .recordingStereo(true).recordingPciPause(false).recordingRetentionDays(90)
            .enableConference(false).maxConferenceRooms(5).maxConferenceParticipants(25)
            .conferenceRecording(true).conferenceDialOut(false).conferenceModerator(true)
            .enableSupervisorFeatures(false).enableSilentMonitor(false)
            .enableWhisper(false).enableBarge(false).enableTakeover(false).enableCoach(false)
            .enableQueues(true).enableSkillsRouting(false).enablePriorityRouting(false)
            .enableCallbackQueue(false).queueMaxWaitTime(600).queueServiceLevel(20).queueOverflowAction("voicemail")
            .enableIvr(true).enableConversationalIvr(false).enableDtmfFallback(true).ivrDefaultLanguage("en-IN")
            .enableVoicemail(true).voicemailMaxDuration(180).voicemailTranscription(false).voicemailEmailNotify(true)
            .enableScreenPop(false).enableOmnichannel(false)
            .enableDncList(false).enableCallDisposition(true).enableAgentScripts(false).complianceRegion("IN");
    }
}
