package com.dalai.llama.product.dto.response;


import lombok.Builder;

import java.math.BigDecimal;

/**
 * Complete entitlements response - ALL feature flags.
 * Maps directly from plan_entitlements table.
 */
@Builder
public record PlanEntitlementResponse(

        // ==================== CAPACITY ====================
        int maxAgents,
        int maxSupervisors,
        int maxConcurrentLogins,
        int maxPstnChannels,
        int maxDids,
        int maxQueues,
        int maxIvrFlows,
        int maxRingGroups,
        int maxExtensions,

        // ==================== CALL DIRECTION ====================
        boolean inboundEnabled,
        boolean outboundEnabled,

        // ==================== AI FEATURES ====================
        boolean aiSttEnabled,
        boolean aiTtsEnabled,
        boolean aiLlmEnabled,
        boolean aiBotEnabled,
        boolean aiSentimentEnabled,
        boolean aiRoutingEnabled,
        boolean aiNoiseCancellationEnabled,
        boolean aiVoiceMorphEnabled,
        boolean aiAgentAssistEnabled,
        long aiTokensPerMonth,
        boolean aiTranscriptionEnabled,
        // ==================== CALL FEATURES ====================
        boolean bargeEnabled,
        boolean whisperEnabled,
        boolean listenEnabled,
        boolean conferenceEnabled,
        boolean callbackEnabled,
        boolean blindTransferEnabled,
        boolean attendedTransferEnabled,
        boolean warmTransferEnabled,

        // ==================== RECORDING ====================
        boolean recordingEnabled,
        int recordingStorageGb,
        int recordingRetentionDays,
        boolean screenRecordingEnabled,

        // ==================== IVR ====================
        boolean basicIvrEnabled,
        boolean conversationalIvrEnabled,
        boolean ivrMultiLanguageEnabled,

        // ==================== DIALER ====================
        boolean progressiveDialerEnabled,
        boolean predictiveDialerEnabled,
        boolean previewDialerEnabled,
        boolean amdEnabled,
        boolean dncManagementEnabled,

        // ==================== VOICEMAIL ====================
        boolean voicemailEnabled,
        boolean voicemailTranscriptionEnabled,

        // ==================== INTEGRATIONS ====================
        boolean crmIntegrationEnabled,
        boolean screenPopEnabled,
        boolean apiAccessEnabled,
        boolean webhookEnabled,

        // ==================== REPORTING ====================
        boolean analyticsEnabled,
        int analyticsRetentionDays,
        boolean basicReportingEnabled,
        boolean advancedReportingEnabled,
        boolean customReportsEnabled,
        boolean wallboardEnabled,

        // ==================== USAGE LIMITS ====================
        int includedMinutesInbound,
        int includedMinutesOutbound,
        BigDecimal ratePerMinuteInbound,
        BigDecimal ratePerMinuteOutbound,
        BigDecimal aiRatePerMinute,

        // ==================== DEPLOYMENT ====================
        boolean dedicatedInfrastructure,
        boolean customDomainEnabled,
        String slaTier

) {}