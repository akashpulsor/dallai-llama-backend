package com.dalai.llama.product.service.impl;

import com.dalai.llama.product.domain.entity.*;
import com.dalai.llama.product.dto.response.PlanPricingResponse;
import com.dalai.llama.product.dto.response.PlanPricingResponse.*;
import com.dalai.llama.product.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class PlanPricingService {

    private final PlanRepository planRepository;
    private final PlanEntitlementRepository entitlementRepository;
    private final PlanAiConfigRepository aiConfigRepository;

    @Transactional(readOnly = true)
    @Cacheable(value = "productPlans", key = "#productCode")
    public List<PlanPricingResponse> getPlansByProductCode(String productCode) {
        List<Plan> plans = planRepository.findByProduct_CodeAndActiveTrue(productCode);
        return plans.stream()
                .map(this::toPricingResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public PlanPricingResponse getPlanPricing(String planCode) {
        Plan plan = planRepository.findByCode(planCode)
                .orElseThrow(() -> new RuntimeException("Plan not found: " + planCode));
        return toPricingResponse(plan);
    }

    private PlanPricingResponse toPricingResponse(Plan plan) {
        Optional<PlanEntitlement> entitlementOpt = entitlementRepository.findByPlan_Id(plan.getId());
        Optional<PlanAiConfig> aiConfigOpt = aiConfigRepository.findByPlanIdWithProviders(plan.getId());

        PlanPricingResponse.PlanPricingResponseBuilder builder = PlanPricingResponse.builder()
                .id(plan.getId())
                .code(plan.getCode())
                .name(plan.getName())
                .tier(plan.getTier() != null ? plan.getTier().name() : null)
                .active(plan.isActive())
                .platformFee(plan.getMonthlyPrice())
                .perAgentFee(plan.getPerAgentFee())
                .setupFee(plan.getSetupFee())
                .includedMinutes(plan.getIncludedMinutes())
                .aiStackType(plan.getAiStackType() != null ? plan.getAiStackType().name() : null)
                .aiRatePerMin(plan.getAiRatePerMin());

        entitlementOpt.ifPresent(e -> {
            builder.includedAgents(plan.getIncludedAgents() != null ? plan.getIncludedAgents() :
                    (e.getMaxAgents() > 0 ? Math.min(e.getMaxAgents(), 5) : 0));
            builder.maxAgents(e.getMaxAgents());
            builder.entitlements(buildEntitlementSummary(e));
        });

        aiConfigOpt.ifPresent(config -> builder.stack(buildAiStackInfo(config)));

        return builder.build();
    }

    private AiStackInfo buildAiStackInfo(PlanAiConfig config) {
        return AiStackInfo.builder()
                .stt(toProviderInfo(config.getSttProvider()))
                .tts(toProviderInfo(config.getTtsProvider()))
                .llm(toProviderInfo(config.getLlmProvider()))
                .build();
    }

    private AiProviderInfo toProviderInfo(AiProvider provider) {
        if (provider == null) return null;
        return AiProviderInfo.builder()
                .provider(provider.getProviderName())
                .model(provider.getModelName())
                .displayName(provider.getDisplayName())
                .cost(provider.getCostPerMin())
                .icon(provider.getIconUrl())
                .color(provider.getBrandColor())
                .latencyMs(provider.getAvgLatencyMs())
                .build();
    }

    private EntitlementSummary buildEntitlementSummary(PlanEntitlement e) {
        List<String> features = new ArrayList<>();
        if (e.isInboundEnabled()) features.add("inbound");
        if (e.isOutboundEnabled()) features.add("outbound");
        if (e.isRecordingEnabled()) features.add("recording");
        if (e.isAnalyticsEnabled()) features.add("analytics");
        if (e.isAiSttEnabled()) features.add("ai_stt");
        if (e.isAiLlmEnabled()) features.add("ai_llm");
        if (e.isAiBotEnabled()) features.add("ai_bot");
        if (e.isAiSentimentEnabled()) features.add("sentiment");

        return EntitlementSummary.builder()
                .maxAgents(e.getMaxAgents())
                .maxDids(e.getMaxDids())
                .maxChannels(e.getMaxPstnChannels())
                .features(features)
                .build();
    }
}