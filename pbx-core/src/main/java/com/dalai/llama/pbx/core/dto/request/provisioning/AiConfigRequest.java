package com.dalai.llama.pbx.core.dto.request.provisioning;



import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Received from tenant-service AiServiceConfigService.configureForSubscription().
 *
 * Tenant-service has already resolved:
 *   - AI mode (product → mode mapping)
 *   - AGI endpoints (dedicated vs shared namespace)
 *   - All feature flags from entitlements
 *
 * PBX-Core stores this as a Redis hash at "ai:config:{tenantId}".
 * voice-brain reads at call time via GET /internal/ai/config/{tenantId}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
public class AiConfigRequest {

    private UUID tenantId;
    private UUID subscriptionId;
    private String productCode;
    private String namespace;
    private Boolean dedicatedInfrastructure;

    // ── AI Mode (resolved from product by tenant-service) ──
    private String aiMode;                    // contact_center, conversational_ivr, receptionist, dialer
    private String greetingType;
    private Boolean intentDetection;
    private Boolean queueCallback;
    private Boolean selfService;
    private Boolean multiTurn;
    private Boolean contextMemory;
    private Double intentThreshold;
    private Boolean clarificationEnabled;
    private Boolean handoffEnabled;
    private Boolean appointmentBooking;
    private Boolean messageTaking;
    private Boolean faqEnabled;
    private Boolean transferEnabled;
    private Boolean businessHoursAware;
    private Boolean campaignScript;
    private Boolean leadQualification;
    private Boolean voicemailDetection;

    // ── Feature flags (from entitlements) ──
    private Boolean sttEnabled;
    private Boolean ttsEnabled;
    private Boolean botEnabled;
    private Boolean routingEnabled;
    private Boolean sentimentEnabled;
    private Boolean noiseCancellationEnabled;
    private Boolean voiceMorphEnabled;
    private Boolean agentAssistEnabled;

    // ── Supervisor features that affect AI ──
    private Boolean whisperEnabled;
    private Boolean listenEnabled;

    // ── IVR / Voicemail / Dialer ──
    private Boolean multiLanguageEnabled;
    private Boolean voicemailTranscriptionEnabled;
    private Boolean amdEnabled;
    private Boolean dncManagementEnabled;

    // ── Usage limits ──
    private Long tokensPerMonth;
    private BigDecimal ratePerMinute;

    // ── AGI endpoints (resolved by tenant-service) ──
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