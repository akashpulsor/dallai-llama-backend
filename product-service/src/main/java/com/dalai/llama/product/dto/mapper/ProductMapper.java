package com.dalai.llama.product.dto.mapper;

import com.dalai.llama.product.domain.entity.*;
import com.dalai.llama.product.dto.response.*;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface ProductMapper {

    // ---------- Product ----------
    ProductResponse toProductResponse(Product product);

    // ---------- Plan ----------
    PlanResponse toPlanResponse(Plan plan);

    default PlanDetailResponse toPlanDetailResponse(
            Plan plan,
            PlanEntitlement entitlement
    ) {
        return PlanDetailResponse.builder()
                .plan(toPlanResponse(plan))
                .entitlements(toEntitlementResponse(entitlement))
                .build();
    }

    // ---------- Plan Assignment ----------
    default PlanAssignmentResponse toPlanAssignmentResponse(PlanAssignment pa) {
        return PlanAssignmentResponse.builder()
                .tenantId(pa.getTenantId())
                .planId(pa.getPlan().getId())
                .planCode(pa.getPlan().getCode())
                .effectiveFrom(pa.getEffectiveFrom())
                .effectiveTo(pa.getEffectiveTo())
                .active(pa.isActive())
                .build();
    }

    // ---------- DID ----------
    DidResponse toDidResponse(Did did);

    // ---------- SIP ----------
    SipTrunkResponse toSipTrunkResponse(SipTrunk trunk);

    SipEndpointResponse toSipEndpointResponse(SipEndpoint endpoint);

    // ---------- Entitlements ----------
    default EntitlementResponse toEntitlementResponse(PlanEntitlement e) {
        return EntitlementResponse.builder()
                .maxAgents(e.getMaxAgents())
                .maxSupervisors(e.getMaxSupervisors())
                .maxConcurrentLogins(e.getMaxConcurrentLogins())
                .maxPstnChannels(e.getMaxPstnChannels())
                .maxDids(e.getMaxDids())
                .inboundEnabled(e.isInboundEnabled())
                .outboundEnabled(e.isOutboundEnabled())
                .recordingEnabled(e.isRecordingEnabled())
                .analyticsEnabled(e.isAnalyticsEnabled())
                .analyticsRetentionDays(e.getAnalyticsRetentionDays())
                .aiSttEnabled(e.isAiSttEnabled())
                .aiLlmEnabled(e.isAiLlmEnabled())
                .aiBotEnabled(e.isAiBotEnabled())
                .aiSentimentEnabled(e.isAiSentimentEnabled())
                .aiTokensPerMonth(e.getAiTokensPerMonth())
                .maxQueues(e.getMaxQueues())
                .maxIvrFlows(e.getMaxIvrFlows())
                .recordingStorageGb(e.getRecordingStorageGb())
                .recordingRetentionDays(e.getRecordingRetentionDays())
                .bargeEnabled(e.isBargeEnabled())
                .whisperEnabled(e.isWhisperEnabled())
                .monitorEnabled(e.isMonitorEnabled())
                .conferenceEnabled(e.isConferenceEnabled())
                .voicemailEnabled(e.isVoicemailEnabled())
                .callbackEnabled(e.isCallbackEnabled())
                .build();
    }
}
