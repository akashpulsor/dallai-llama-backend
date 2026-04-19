package com.dalai.llama.tenant.service.impl;

import com.dalai.llama.tenant.domain.entity.TenantApp;
import com.dalai.llama.tenant.dto.request.AiConfigRequest;
import com.dalai.llama.tenant.dto.response.PlanEntitlementResponse;
import com.dalai.llama.tenant.service.client.PbxCoreClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiServiceConfigService {

    private final PbxCoreClient pbxCoreClient;


    public void configureForSubscription(TenantApp app, PlanEntitlementResponse e) {
        log.info("Configuring AI Service for {} - product: {}",
                app.getSubscriptionId(), app.getProductCode());

        boolean dedicated = Boolean.TRUE.equals(app.getDedicatedInfrastructure());

        // ── Resolve product-specific AI mode (business logic stays here) ──
        AiModeConfig modeConfig = resolveAiMode(app.getProductCode(), e);

        AiConfigRequest request = AiConfigRequest.builder()
                .tenantId(app.getTenant().getId())
                .subscriptionId(app.getSubscriptionId())
                .productCode(app.getProductCode())
                .namespace(app.getNamespace())
                .dedicatedInfrastructure(dedicated)
                // Resolved AI mode (product logic)
                .aiMode(modeConfig.aiMode)
                .greetingType(modeConfig.greetingType)
                .intentDetection(modeConfig.intentDetection)
                .queueCallback(modeConfig.queueCallback)
                .selfService(modeConfig.selfService)
                .multiTurn(modeConfig.multiTurn)
                .contextMemory(modeConfig.contextMemory)
                .intentThreshold(modeConfig.intentThreshold)
                .clarificationEnabled(modeConfig.clarificationEnabled)
                .handoffEnabled(modeConfig.handoffEnabled)
                .appointmentBooking(modeConfig.appointmentBooking)
                .messageTaking(modeConfig.messageTaking)
                .faqEnabled(modeConfig.faqEnabled)
                .transferEnabled(modeConfig.transferEnabled)
                .businessHoursAware(modeConfig.businessHoursAware)
                .campaignScript(modeConfig.campaignScript)
                .leadQualification(modeConfig.leadQualification)
                .voicemailDetection(modeConfig.voicemailDetection)
                // AI features from entitlements
                .sttEnabled(e.aiSttEnabled())
                .ttsEnabled(e.aiTtsEnabled())
                .botEnabled(e.aiBotEnabled())
                .routingEnabled(e.aiRoutingEnabled())
                .sentimentEnabled(e.aiSentimentEnabled())
                .noiseCancellationEnabled(e.aiNoiseCancellationEnabled())
                .voiceMorphEnabled(e.aiVoiceMorphEnabled())
                .agentAssistEnabled(e.aiAgentAssistEnabled())
                // Supervisor features that affect AI
                .whisperEnabled(e.whisperEnabled())
                .listenEnabled(e.listenEnabled())
                // IVR / Voicemail / Dialer
                .multiLanguageEnabled(e.ivrMultiLanguageEnabled())
                .voicemailTranscriptionEnabled(e.voicemailTranscriptionEnabled())
                .amdEnabled(e.amdEnabled())
                .dncManagementEnabled(e.dncManagementEnabled())
                // Usage limits
                .tokensPerMonth(e.aiTokensPerMonth())
                .ratePerMinute(e.aiRatePerMinute())

                .build();

        pbxCoreClient.configureAi(request);

        log.info("AI config sent to PBX-Core for {} [mode={}]",
                app.getNamespace(), modeConfig.aiMode);
    }

    /**
     * Product-to-AI-mode resolution. This is BUSINESS LOGIC — stays in tenant-service.
     * PBX-Core should never know what "AI_CC" or "CONV_IVR" means as products.
     */
    private AiModeConfig resolveAiMode(String productCode, PlanEntitlementResponse e) {
        return switch (productCode) {
            case "AI_CC" -> AiModeConfig.builder()
                    .aiMode("contact_center")
                    .greetingType("ai_dynamic")
                    .intentDetection(true)
                    .queueCallback(true)
                    .selfService(true)
                    .build();
            case "CONV_IVR" -> AiModeConfig.builder()
                    .aiMode("conversational_ivr")
                    .greetingType("ai_conversational")
                    .multiTurn(true)
                    .contextMemory(true)
                    .intentThreshold("0.7")
                    .clarificationEnabled(true)
                    .handoffEnabled(true)
                    .build();
            case "VIRTUAL_RECEPTIONIST" -> AiModeConfig.builder()
                    .aiMode("receptionist")
                    .greetingType("ai_receptionist")
                    .appointmentBooking(true)
                    .messageTaking(true)
                    .faqEnabled(true)
                    .transferEnabled(true)
                    .businessHoursAware(true)
                    .build();
            case "OUTBOUND_DIALER" -> AiModeConfig.builder()
                    .aiMode("dialer")
                    .greetingType("static")
                    .voicemailDetection(true)
                    .campaignScript(true)
                    .leadQualification(true)
                    .build();
            case "BASIC_PBX" -> AiModeConfig.builder()
                    .aiMode("basic")
                    .greetingType("static")
                    .build();
            default -> AiModeConfig.builder()
                    .aiMode("disabled")
                    .greetingType("static")
                    .build();
        };
    }

    public void removeSubscriptionConfig(String tenantId) {
        log.info("Removing AI config via PBX-Core for tenant {}", tenantId);
        pbxCoreClient.removeAiConfig(tenantId);
    }

    /**
     * Internal helper — product-specific AI behavior resolved values.
     */
    @lombok.Builder
    private static record AiModeConfig(
            String aiMode,
            String greetingType,
            Boolean intentDetection,
            Boolean queueCallback,
            Boolean selfService,
            Boolean multiTurn,
            Boolean contextMemory,
            String intentThreshold,
            Boolean clarificationEnabled,
            Boolean handoffEnabled,
            Boolean appointmentBooking,
            Boolean messageTaking,
            Boolean faqEnabled,
            Boolean transferEnabled,
            Boolean businessHoursAware,
            Boolean voicemailDetection,
            Boolean campaignScript,
            Boolean leadQualification
    ) {}
}