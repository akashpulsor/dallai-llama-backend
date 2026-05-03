package com.dalai.llama.product.domain.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Plan Entitlements - Complete feature matrix for each plan.
 *
 * This is the SOURCE OF TRUTH for all feature flags.
 * tenant-service reads this via internal API.
 */
@Entity
@Table(name = "plan_entitlements")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PlanEntitlement {

    @Id
    @Builder.Default
    private UUID id = UUID.randomUUID();

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "plan_id", nullable = false, unique = true)
    private Plan plan;

    // ==================== CAPACITY LIMITS ====================

    @Column(name = "max_agents")
    @Builder.Default
    private Integer maxAgents = 5;

    @Column(name = "max_supervisors")
    @Builder.Default
    private Integer maxSupervisors = 1;

    @Column(name = "max_pstn_channels")
    @Builder.Default
    private Integer maxPstnChannels = 5;

    @Column(name = "max_dids")
    @Builder.Default
    private Integer maxDids = 1;

    @Column(name = "max_queues")
    @Builder.Default
    private Integer maxQueues = 3;

    @Column(name = "max_ivr_flows")
    @Builder.Default
    private Integer maxIvrFlows = 2;

    @Column(name = "max_ring_groups")
    @Builder.Default
    private Integer maxRingGroups = 3;

    @Column(name = "max_extensions")
    @Builder.Default
    private Integer maxExtensions = 10;

    // ==================== AI FEATURES ====================

    @Column(name = "ai_stt_enabled")
    @Builder.Default
    private boolean aiSttEnabled = false;

    @Column(name = "ai_tts_enabled")
    @Builder.Default
    private boolean aiTtsEnabled = false;

    @Column(name = "ai_llm_enabled")
    @Builder.Default
    private boolean aiLlmEnabled = false;

    @Column(name = "ai_sentiment_enabled")
    @Builder.Default
    private boolean aiSentimentEnabled = false;

    @Column(name = "ai_bot_enabled")
    @Builder.Default
    private boolean aiBotEnabled = false;

    @Column(name = "ai_routing_enabled")
    @Builder.Default
    private boolean aiRoutingEnabled = false;

    @Column(name = "ai_noise_cancellation_enabled")
    @Builder.Default
    private boolean aiNoiseCancellationEnabled = false;

    @Column(name = "ai_voice_morph_enabled")
    @Builder.Default
    private boolean aiVoiceMorphEnabled = false;

    @Column(name = "ai_agent_assist_enabled")
    @Builder.Default
    private boolean aiAgentAssistEnabled = false;

    @Column(name = "ai_tokens_per_month")
    @Builder.Default
    private Long  aiTokensPerMonth = 0L;

    @Column(name = "ai_transcription_enabled")
    @Builder.Default
    private boolean aiTranscriptionEnabled = false;

    // ==================== CALL FEATURES ====================

    @Column(name = "barge_enabled")
    @Builder.Default
    private boolean bargeEnabled = false;

    @Column(name = "whisper_enabled")
    @Builder.Default
    private boolean whisperEnabled = false;

    @Column(name = "listen_enabled")
    @Builder.Default
    private boolean listenEnabled = false;

    @Column(name = "conference_enabled")
    @Builder.Default
    private boolean conferenceEnabled = false;

    @Column(name = "callback_enabled")
    @Builder.Default
    private boolean callbackEnabled = false;

    @Column(name = "blind_transfer_enabled")
    @Builder.Default
    private boolean blindTransferEnabled = true;

    @Column(name = "attended_transfer_enabled")
    @Builder.Default
    private boolean attendedTransferEnabled = false;

    @Column(name = "warm_transfer_enabled")
    @Builder.Default
    private boolean warmTransferEnabled = false;

    // ==================== RECORDING ====================

    @Column(name = "recording_enabled")
    @Builder.Default
    private boolean recordingEnabled = false;

    @Column(name = "recording_storage_gb")
    @Builder.Default
    private Integer recordingStorageGb = 0;

    @Column(name = "recording_retention_days")
    @Builder.Default
    private Integer recordingRetentionDays = 30;

    @Column(name = "screen_recording_enabled")
    @Builder.Default
    private boolean screenRecordingEnabled = false;

    @Column(name = "monitor_enabled")
    @Builder.Default
    private boolean monitorEnabled = false;


    // ==================== IVR FEATURES ====================

    @Column(name = "basic_ivr_enabled")
    @Builder.Default
    private boolean basicIvrEnabled = true;

    @Column(name = "conversational_ivr_enabled")
    @Builder.Default
    private boolean conversationalIvrEnabled = false;

    @Column(name = "ivr_multi_language_enabled")
    @Builder.Default
    private boolean ivrMultiLanguageEnabled = false;

    // ==================== DIALER FEATURES ====================

    @Column(name = "progressive_dialer_enabled")
    @Builder.Default
    private boolean progressiveDialerEnabled = false;

    @Column(name = "predictive_dialer_enabled")
    @Builder.Default
    private boolean predictiveDialerEnabled = false;

    @Column(name = "preview_dialer_enabled")
    @Builder.Default
    private boolean previewDialerEnabled = false;

    @Column(name = "amd_enabled")
    @Builder.Default
    private boolean amdEnabled = false;

    @Column(name = "dnc_management_enabled")
    @Builder.Default
    private boolean dncManagementEnabled = false;

    // ==================== VOICEMAIL ====================

    @Column(name = "voicemail_enabled")
    @Builder.Default
    private boolean voicemailEnabled = true;

    @Column(name = "voicemail_transcription_enabled")
    @Builder.Default
    private boolean voicemailTranscriptionEnabled = false;

    // ==================== INTEGRATIONS ====================

    @Column(name = "crm_integration_enabled")
    @Builder.Default
    private boolean crmIntegrationEnabled = false;

    @Column(name = "screen_pop_enabled")
    @Builder.Default
    private boolean screenPopEnabled = false;

    @Column(name = "api_access_enabled")
    @Builder.Default
    private boolean apiAccessEnabled = false;

    @Column(name = "webhook_enabled")
    @Builder.Default
    private boolean webhookEnabled = false;

    // ==================== REPORTING ====================

    @Column(name = "basic_reporting_enabled")
    @Builder.Default
    private boolean basicReportingEnabled = true;

    @Column(name = "advanced_reporting_enabled")
    @Builder.Default
    private boolean advancedReportingEnabled = false;

    @Column(name = "custom_reports_enabled")
    @Builder.Default
    private boolean customReportsEnabled = false;

    @Column(name = "wallboard_enabled")
    @Builder.Default
    private boolean wallboardEnabled = false;

    // ==================== USAGE LIMITS ====================

    @Column(name = "included_minutes_inbound")
    @Builder.Default
    private Integer includedMinutesInbound = 0;

    @Column(name = "included_minutes_outbound")
    @Builder.Default
    private Integer includedMinutesOutbound = 0;

    @Column(name = "rate_per_minute_inbound", precision = 10, scale = 4)
    @Builder.Default
    private BigDecimal ratePerMinuteInbound = BigDecimal.ZERO;

    @Column(name = "rate_per_minute_outbound", precision = 10, scale = 4)
    @Builder.Default
    private BigDecimal ratePerMinuteOutbound = BigDecimal.ZERO;

    @Column(name = "ai_rate_per_minute", precision = 10, scale = 4)
    @Builder.Default
    private BigDecimal aiRatePerMinute = BigDecimal.ZERO;

    // ==================== DEPLOYMENT ====================

    @Column(name = "dedicated_infrastructure")
    @Builder.Default
    private boolean dedicatedInfrastructure = false;

    @Column(name = "custom_domain_enabled")
    @Builder.Default
    private boolean customDomainEnabled = false;

    @Column(name = "sla_tier", length = 20)
    @Builder.Default
    private String slaTier = "STANDARD";

// ==================== CALL DIRECTION ====================

    @Column(name = "inbound_enabled")
    @Builder.Default
    private boolean inboundEnabled = true;

    @Column(name = "outbound_enabled")
    @Builder.Default
    private boolean outboundEnabled = true;

// ==================== ANALYTICS ====================

    @Column(name = "analytics_enabled")
    @Builder.Default
    private boolean analyticsEnabled = false;


    @Column(name = "analytics_retention_days")
    @Builder.Default
    private Integer analyticsRetentionDays = 30;

    // ==================== TIMESTAMPS ====================

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
        createdAt = updatedAt = Instant.now();
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}