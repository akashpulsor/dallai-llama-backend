package com.dalai.llama.tenant.service.provisioning.telecom.model;

import lombok.Data;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * DTO representing the CC Builder wizard output.
 * Maps directly from frontend JSON to backend config.
 */
@Data
public class CCBuilderConfig {
    
    private Metadata metadata;
    private TenantConfig tenant;
    private Entitlements entitlements;
    private List<AddOn> addOns;
    private List<DidConfig> dids;
    private TelephonyConfig telephony;
    private AiConfig aiConfig;
    private PricingConfig pricing;
    
    @Data
    public static class Metadata {
        private String generatedAt;
        private String version;
        private String configType;
    }
    
    @Data
    public static class TenantConfig {
        private String productCode;      // AI_CC, CONV_IVR, BASIC_PBX, OUTBOUND_DIALER
        private String planCode;         // STARTER, PROFESSIONAL, ENTERPRISE
        private String deploymentModel;  // SHARED, DEDICATED, PREMIUM
    }
    
    @Data
    public static class Entitlements {
        private int maxAgents;
        private int maxDids;
        private int maxQueues;
        private int maxIvrFlows;
        private int maxConcurrentCalls;
        private int recordingStorageGb;
        private int aiMinutesPerMonth;
        private int maxConferenceRooms;
        private int maxConferenceParticipants;
        private Features features;
    }
    
    @Data
    public static class Features {
        private boolean realTimeTranscription;
        private boolean sentimentAnalysis;
        private boolean agentAssist;
        private boolean customIntegrations;
        private boolean apiAccess;
        private boolean whiteLabel;
        private boolean dedicatedSupport;
        private boolean slaGuarantee;
        private boolean conferenceCall;
        private boolean whisperCoach;
        private boolean bargeIn;
    }
    
    @Data
    public static class AddOn {
        private String code;
        private int quantity;
        private String name;
        private String category;
        private double monthlyPrice;
    }
    
    @Data
    public static class DidConfig {
        private String number;
        private String country;
        private String city;
        private String type;  // GEOGRAPHIC, TOLL_FREE, MOBILE
    }
    
    @Data
    public static class TelephonyConfig {
        private KamailioConfig kamailioConfig;
        private FreeSwitchConfig freeSwitchConfig;
        private RtpEngineConfig rtpEngineConfig;
        private CoTurnConfig coTurnConfig;
    }
    
    @Data
    public static class KamailioConfig {
        private int maxRegistrations;
        private int maxConcurrentCalls;
        private boolean webSocketEnabled;
        private boolean tlsEnabled;
    }
    
    @Data
    public static class FreeSwitchConfig {
        private int maxChannels;
        private boolean recordingEnabled;
        private String recordingFormat;
        private int recordingStorageGb;
        private boolean conferenceEnabled;
        private ConferenceConfig conferenceConfig;
    }
    
    @Data
    public static class ConferenceConfig {
        private boolean enabled;
        private int maxRooms;
        private int maxParticipantsPerRoom;
        private boolean recordingEnabled;
        private boolean dialOutEnabled;
        private boolean webRtcEnabled;
        private boolean moderatorPin;
    }
    
    @Data
    public static class RtpEngineConfig {
        private int minPort;
        private int maxPort;
        private boolean dtlsSrtpEnabled;
    }
    
    @Data
    public static class CoTurnConfig {
        private boolean enabled;
        private String realm;
    }
    
    @Data
    public static class AiConfig {
        private boolean transcriptionEnabled;
        private boolean sentimentEnabled;
        private boolean agentAssistEnabled;
        private boolean acwAutomationEnabled;
        private boolean conversationalIvrEnabled;
        private boolean leadScoringEnabled;
        private int aiMinutesPerMonth;
    }
    
    @Data
    public static class PricingConfig {
        private double monthlyTotal;
        private List<PriceBreakdown> breakdown;
        private String currency;
    }
    
    @Data
    public static class PriceBreakdown {
        private String name;
        private double amount;
    }
    
    // ============================================================
    // CONVERSION TO TELECOM STACK CONFIG
    // ============================================================
    
    /**
     * Convert CCBuilderConfig to TelecomStackConfig for deployment.
     */
    public TelecomStackConfig toTelecomStackConfig(String tenantId, String tenantSlug, 
            String pbxCoreUrl, String aiServiceUrl, String kafkaBootstrap, String redisUrl) {
        
        boolean isDedicated = "DEDICATED".equals(tenant.getDeploymentModel()) || 
                              "PREMIUM".equals(tenant.getDeploymentModel());
        
        // Check which add-ons are enabled
        Set<String> enabledAddOns = new java.util.HashSet<>();
        if (addOns != null) {
            addOns.forEach(a -> enabledAddOns.add(a.getCode()));
        }
        
        boolean hasConference = entitlements.getFeatures().isConferenceCall() || 
                               enabledAddOns.contains("CONFERENCE");
        boolean hasSupervisor = entitlements.getFeatures().isWhisperCoach() || 
                               entitlements.getFeatures().isBargeIn() ||
                               enabledAddOns.contains("WHISPER_BARGE");
        boolean hasAcw = enabledAddOns.contains("ACW_AUTOMATION");
        boolean hasConvIvr = enabledAddOns.contains("LIVE_CONV_IVR");
        boolean hasSkillsRouting = enabledAddOns.contains("ADV_ROUTING");
        boolean hasCallbackQueue = enabledAddOns.contains("ADV_ROUTING");
        boolean hasOutboundDialer = "OUTBOUND_DIALER".equals(tenant.getProductCode()) ||
                                    enabledAddOns.contains("OUTBOUND_DIALER");
        boolean hasOmnichannel = enabledAddOns.contains("OMNICHANNEL");
        boolean hasQm = enabledAddOns.contains("QM");
        boolean hasWfm = enabledAddOns.contains("WFM");
        boolean hasCrm = enabledAddOns.contains("CRM_INT");
        boolean hasRecordingPro = enabledAddOns.contains("RECORDING_PRO");
        
        ConferenceConfig confCfg = telephony != null && telephony.getFreeSwitchConfig() != null 
            ? telephony.getFreeSwitchConfig().getConferenceConfig() 
            : null;
        
        return TelecomStackConfig.builder()
            // Tenant
            .tenantId(tenantId)
            .tenantSlug(tenantSlug)
            .namespace("tenant-" + tenantSlug)
            .realm(tenantSlug + ".dalaillama.in")
            .isDedicated(isDedicated)
            
            // URLs
            .pbxCoreUrl(pbxCoreUrl)
            .aiServiceUrl(aiServiceUrl)
            .kafkaBootstrap(kafkaBootstrap)
            .kafkaTopicPrefix("tenant-" + tenantSlug)
            .redisUrl(redisUrl)
            
            // WebRTC
            .enableWebRtc(true)
            .wssPort(8443)
            .turnPort(3478)
            .turnTlsPort(5349)
            
            // SIP
            .sipUdpPort(5060)
            .sipTcpPort(5060)
            .sipTlsPort(5061)
            
            // Media
            .rtpPortRangeStart(telephony != null && telephony.getRtpEngineConfig() != null 
                ? telephony.getRtpEngineConfig().getMinPort() : 30000)
            .rtpPortRangeEnd(telephony != null && telephony.getRtpEngineConfig() != null 
                ? telephony.getRtpEngineConfig().getMaxPort() : 40000)
            
            // ESL
            .eslPort(8021)
            
            // Capacity
            .maxAgents(entitlements.getMaxAgents())
            .maxConcurrentCalls(entitlements.getMaxConcurrentCalls())
            .maxQueues(entitlements.getMaxQueues())
            .maxIvrFlows(entitlements.getMaxIvrFlows())
            .maxDids(entitlements.getMaxDids())
            
            // Recording
            .enableRecording(telephony != null && telephony.getFreeSwitchConfig() != null 
                && telephony.getFreeSwitchConfig().isRecordingEnabled())
            .recordingPath("/recordings")
            .recordingFormat(telephony != null && telephony.getFreeSwitchConfig() != null 
                ? telephony.getFreeSwitchConfig().getRecordingFormat() : "wav")
            .recordingStereo(true)
            .recordingPciPause(hasRecordingPro)
            .recordingRetentionDays(90)
            
            // AI
            .enableAiTranscription(aiConfig != null && aiConfig.isTranscriptionEnabled())
            .enableAiSentiment(aiConfig != null && aiConfig.isSentimentEnabled())
            .enableAiAgentAssist(aiConfig != null && aiConfig.isAgentAssistEnabled())
            .enableAcwAutomation(hasAcw)
            .enableAiRouting(enabledAddOns.contains("LEAD_SCORING"))
            .enableAiNoiseCancellation(enabledAddOns.contains("AUDIO_INTELLIGENCE"))
            .aiMinutesPerMonth(entitlements.getAiMinutesPerMonth())
            
            // Conference
            .enableConference(hasConference)
            .maxConferenceRooms(confCfg != null ? confCfg.getMaxRooms() : entitlements.getMaxConferenceRooms())
            .maxConferenceParticipants(confCfg != null ? confCfg.getMaxParticipantsPerRoom() : entitlements.getMaxConferenceParticipants())
            .conferenceRecording(confCfg != null && confCfg.isRecordingEnabled())
            .conferenceDialOut(confCfg != null && confCfg.isDialOutEnabled())
            .conferenceModerator(confCfg != null && confCfg.isModeratorPin())
            .conferenceDefaultProfile("wideband")
            
            // Supervisor
            .enableSupervisorFeatures(hasSupervisor)
            .enableSilentMonitor(hasSupervisor)
            .enableWhisper(entitlements.getFeatures().isWhisperCoach() || enabledAddOns.contains("WHISPER_BARGE"))
            .enableBarge(entitlements.getFeatures().isBargeIn() || enabledAddOns.contains("WHISPER_BARGE"))
            .enableTakeover(hasSupervisor)
            .enableCoach(hasSupervisor)
            
            // Queues
            .enableQueues(true)
            .enableSkillsRouting(hasSkillsRouting)
            .enablePriorityRouting(hasSkillsRouting)
            .enableCallbackQueue(hasCallbackQueue)
            .queueMaxWaitTime(600)
            .queueServiceLevel(20)
            .queueOverflowAction("voicemail")
            
            // Dialer
            .enableOutboundDialer(hasOutboundDialer)
            .enablePredictiveDialing(hasOutboundDialer)
            .enableProgressiveDialing(hasOutboundDialer)
            .enablePreviewDialing(hasOutboundDialer)
            .dialerMaxConcurrent(10)
            .dialerPacingRatio(2)
            
            // IVR
            .enableIvr(true)
            .enableConversationalIvr(hasConvIvr)
            .enableDtmfFallback(true)
            .ivrDefaultLanguage("en-IN")
            
            // Voicemail
            .enableVoicemail(true)
            .voicemailMaxDuration(180)
            .voicemailTranscription(aiConfig != null && aiConfig.isTranscriptionEnabled())
            .voicemailEmailNotify(true)
            
            // Screen Pop / CRM
            .enableScreenPop(hasCrm)
            .crmIntegrationType(hasCrm ? "custom" : null)
            
            // Omnichannel
            .enableOmnichannel(hasOmnichannel)
            .enableWhatsApp(hasOmnichannel)
            .enableSms(hasOmnichannel)
            .enableEmail(hasOmnichannel)
            .enableWebChat(hasOmnichannel)
            
            // Compliance
            .enableDncList(hasOutboundDialer)
            .enableCallDisposition(true)
            .enableAgentScripts(hasQm)
            .complianceRegion("IN")
            
            .build();
    }
}
