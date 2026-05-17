package com.dalai.llama.tenant.dto.response;

import com.dalai.llama.tenant.domain.entity.AppPanel;
import com.dalai.llama.tenant.domain.entity.TenantApp;
import com.dalai.llama.tenant.domain.entity.enums.ProvisioningTaskStatus;
import lombok.Builder;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import java.util.Arrays;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Detailed view of a TenantApp/subscription. Use this for the
 * "subscription details" page; use TenantAppSummary for sidebars.
 *
 * Only returned for COMPLETED apps — callers should not see infra URLs,
 * SIP creds, or feature flags for half-provisioned subscriptions.
 * Detailed view of a TenantApp/subscription. Use this for the
 * "subscription details" page; use TenantAppSummary for sidebars.
 *
 * Only returned for COMPLETED apps — callers should not see infra URLs,
 * SIP creds, or feature flags for half-provisioned subscriptions.
 */

@Slf4j
@Builder
public record SubscriptionDetailResponse(
        // ── Summary fields (mirrors TenantAppSummary for UI continuity) ──
        UUID id,
        UUID tenantId,
        UUID subscriptionId,
        String appType,
        String displayName,
        String subdomain,
        String productCode,
        String planCode,
        String planTier,
        ProvisioningTaskStatus deploymentStatus,
        OffsetDateTime createdAt,
        Instant deployedAt,

        // ── Capacity entitlements ──
        Integer agentSeats,
        Integer maxAgents,
        Integer maxSupervisors,
        Integer maxDids,
        Integer maxChannels,
        Integer maxQueues,
        Integer maxIvrFlows,
        Integer maxRingGroups,
        Integer maxExtensions,

        // ── App panels (parsed from TenantApp.appPanels JSON) ──
        List<AppPanel> appPanels,
        AdminCredentials adminCredentials,

        // ── Usage ──
        Integer includedMinutes,
        Integer includedMinutesInbound,
        Integer includedMinutesOutbound,
        BigDecimal ratePerMinuteInbound,
        BigDecimal ratePerMinuteOutbound,
        BigDecimal aiRatePerMinute,
        Integer aiTokensPerMonth,

        // ── Recording ──
        Boolean recordingEnabled,
        Integer recordingStorageGb,
        Integer recordingRetentionDays,

        // ── DID ──
        UUID didId,
        String didNumber,
        String didDisplayNumber,
        String didCountry,
        String didRegion,
        String didCity,

        // ── Channels ──
        Integer channelTotal,
        Integer channelInbound,
        Integer channelOutbound,

        // ── Feature toggles (flat group for UI feature matrix) ──
        Features features,

        // ── Deployment ──
        String dashboardUrl,
        String slaTier,
        Boolean dedicatedInfrastructure,
        Boolean customDomainEnabled
) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Builder
    public record Features(
            // AI
            Boolean aiTranscription,
            Boolean aiRouting,
            Boolean aiNoiseCancellation,
            Boolean aiSentiment,
            Boolean aiBot,
            Boolean aiVoiceMorph,
            Boolean aiAgentAssist,
            // Call
            Boolean barge,
            Boolean whisper,
            Boolean listen,
            Boolean conference,
            Boolean callback,
            Boolean blindTransfer,
            Boolean attendedTransfer,
            Boolean warmTransfer,
            // IVR
            Boolean basicIvr,
            Boolean conversationalIvr,
            Boolean ivrMultiLanguage,
            // Dialer
            Boolean progressiveDialer,
            Boolean predictiveDialer,
            Boolean previewDialer,
            Boolean amd,
            Boolean dncManagement,
            // Voicemail
            Boolean voicemail,
            Boolean voicemailTranscription,
            // Integrations
            Boolean crmIntegration,
            Boolean screenPop,
            Boolean apiAccess,
            Boolean webhook,
            // Reporting
            Boolean basicReporting,
            Boolean advancedReporting,
            Boolean customReports,
            Boolean wallboard,
            // Recording
            Boolean screenRecording
    ) {}

    /**
     * Parse the appPanels JSON string stored on TenantApp into a list of AppPanel
     * objects. Returns empty list on null/blank or malformed JSON — never throws
     * so a corrupt row doesn't break the detail endpoint.
     */
    private static List<AppPanel> parseAppPanels(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return Arrays.asList(MAPPER.readValue(json, AppPanel[].class));
        } catch (Exception e) {
            log.error("Failed to parse appPanels JSON: {}", e.getMessage());
            return List.of();
        }
    }

    public static SubscriptionDetailResponse from(TenantApp app) {
        return from(app, null);
    }

    public static SubscriptionDetailResponse from(TenantApp app, AdminCredentials adminCredentials) {
        return SubscriptionDetailResponse.builder()
                .id(app.getId())
                .tenantId(app.getTenant() != null ? app.getTenant().getId() : null)
                .subscriptionId(app.getSubscriptionId())
                .appType(app.getAppType() != null ? app.getAppType().name() : null)
                .displayName(app.getDisplayName())
                .subdomain(app.getSubdomain())
                .productCode(app.getProductCode())
                .planCode(app.getPlanCode())
                .planTier(app.getPlanTier())
                .deploymentStatus(app.getDeploymentStatus())
                .createdAt(app.getCreatedAt())
                .deployedAt(app.getDeployedAt())

                .agentSeats(app.getAgentSeats())
                .maxAgents(app.getMaxAgents())
                .maxSupervisors(app.getMaxSupervisors())
                .maxDids(app.getMaxDids())
                .maxChannels(app.getMaxChannels())
                .maxQueues(app.getMaxQueues())
                .maxIvrFlows(app.getMaxIvrFlows())
                .maxRingGroups(app.getMaxRingGroups())
                .maxExtensions(app.getMaxExtensions())

                .appPanels(parseAppPanels(app.getAppPanels()))
                .adminCredentials(adminCredentials)

                .includedMinutes(app.getIncludedMinutes())
                .includedMinutesInbound(app.getIncludedMinutesInbound())
                .includedMinutesOutbound(app.getIncludedMinutesOutbound())
                .ratePerMinuteInbound(app.getRatePerMinuteInbound())
                .ratePerMinuteOutbound(app.getRatePerMinuteOutbound())
                .aiRatePerMinute(app.getAiRatePerMinute())
                .aiTokensPerMonth(app.getAiTokensPerMonth())

                .recordingEnabled(app.getRecordingEnabled())
                .recordingStorageGb(app.getRecordingStorageGb())
                .recordingRetentionDays(app.getRecordingRetentionDays())

                .didId(app.getDidId())
                .didNumber(app.getDidNumber())
                .didDisplayNumber(app.getDidDisplayNumber())
                .didCountry(app.getDidCountry())
                .didRegion(app.getDidRegion())
                .didCity(app.getDidCity())

                .channelTotal(app.getChannelTotal())
                .channelInbound(app.getChannelInbound())
                .channelOutbound(app.getChannelOutbound())

                .features(Features.builder()
                        .aiTranscription(app.getAiTranscriptionEnabled())
                        .aiRouting(app.getAiRoutingEnabled())
                        .aiNoiseCancellation(app.getAiNoiseCancellationEnabled())
                        .aiSentiment(app.getAiSentimentEnabled())
                        .aiBot(app.getAiBotEnabled())
                        .aiVoiceMorph(app.getAiVoiceMorphEnabled())
                        .aiAgentAssist(app.getAiAgentAssistEnabled())
                        .barge(app.getBargeEnabled())
                        .whisper(app.getWhisperEnabled())
                        .listen(app.getListenEnabled())
                        .conference(app.getConferenceEnabled())
                        .callback(app.getCallbackEnabled())
                        .blindTransfer(app.getBlindTransferEnabled())
                        .attendedTransfer(app.getAttendedTransferEnabled())
                        .warmTransfer(app.getWarmTransferEnabled())
                        .basicIvr(app.getBasicIvrEnabled())
                        .conversationalIvr(app.getConversationalIvrEnabled())
                        .ivrMultiLanguage(app.getIvrMultiLanguageEnabled())
                        .progressiveDialer(app.getProgressiveDialerEnabled())
                        .predictiveDialer(app.getPredictiveDialerEnabled())
                        .previewDialer(app.getPreviewDialerEnabled())
                        .amd(app.getAmdEnabled())
                        .dncManagement(app.getDncManagementEnabled())
                        .voicemail(app.getVoicemailEnabled())
                        .voicemailTranscription(app.getVoicemailTranscriptionEnabled())
                        .crmIntegration(app.getCrmIntegrationEnabled())
                        .screenPop(app.getScreenPopEnabled())
                        .apiAccess(app.getApiAccessEnabled())
                        .webhook(app.getWebhookEnabled())
                        .basicReporting(app.getBasicReportingEnabled())
                        .advancedReporting(app.getAdvancedReportingEnabled())
                        .customReports(app.getCustomReportsEnabled())
                        .wallboard(app.getWallboardEnabled())
                        .screenRecording(app.getScreenRecordingEnabled())
                        .build())

                .dashboardUrl(app.getDashboardUrl())
                .slaTier(app.getSlaTier())
                .dedicatedInfrastructure(app.getDedicatedInfrastructure())
                .customDomainEnabled(app.getCustomDomainEnabled())
                .build();
    }
}
