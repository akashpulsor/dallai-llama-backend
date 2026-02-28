package com.dalai.llama.tenant.dto.request;

import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;
import java.util.UUID;

@Data
@Builder
public class AiConfigRequest {
    private UUID tenantId;
    private UUID subscriptionId;
    private String productCode;
    private String namespace;
    private Boolean dedicatedInfrastructure;

    // ── Resolved AI Mode (product logic already applied) ──
    private String aiMode;           // contact_center, conversational_ivr, receptionist, dialer, basic, disabled
    private String greetingType;     // ai_dynamic, ai_conversational, ai_receptionist, static
    private Boolean intentDetection;
    private Boolean queueCallback;
    private Boolean selfService;
    private Boolean multiTurn;
    private Boolean contextMemory;
    private String intentThreshold;
    private Boolean clarificationEnabled;
    private Boolean handoffEnabled;
    private Boolean appointmentBooking;
    private Boolean messageTaking;
    private Boolean faqEnabled;
    private Boolean transferEnabled;
    private Boolean businessHoursAware;
    private Boolean voicemailDetection;
    private Boolean campaignScript;
    private Boolean leadQualification;

    // ── AI Feature Flags (from entitlements) ──
    private Boolean sttEnabled;
    private Boolean ttsEnabled;
    private Boolean botEnabled;
    private Boolean routingEnabled;
    private Boolean sentimentEnabled;
    private Boolean noiseCancellationEnabled;
    private Boolean voiceMorphEnabled;
    private Boolean agentAssistEnabled;

    // ── Supervisor features ──
    private Boolean whisperEnabled;
    private Boolean listenEnabled;

    // ── IVR / Voicemail / Dialer ──
    private Boolean multiLanguageEnabled;
    private Boolean voicemailTranscriptionEnabled;
    private Boolean amdEnabled;
    private Boolean dncManagementEnabled;

    // ── Usage Limits ──
    private Long tokensPerMonth;
    private BigDecimal ratePerMinute;

    // ── Resolved AGI Endpoints (tenant-service knows namespace) ──
    private String aiAgiUrl;
    private String aiHttpUrl;
    private String agiGreeting;
    private String agiConversation;
    private String agiIntent;
    private String agiSelfservice;
    private String agiFaq;
    private String agiReceptionist;
    private String agiAppointment;
    private String agiMessage;
    private String agiTranscribe;
    private String agiAmdResult;
    private String agiDialerResult;
}