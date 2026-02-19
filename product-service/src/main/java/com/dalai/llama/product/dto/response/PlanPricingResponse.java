package com.dalai.llama.product.dto.response;

import lombok.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlanPricingResponse {

    private UUID id;
    private String code;
    private String name;
    private String tier;
    private boolean active;

    private BigDecimal platformFee;
    private BigDecimal perAgentFee;
    private BigDecimal setupFee;

    private Integer includedAgents;
    private Integer maxAgents;
    private Integer includedMinutes;

    private String aiStackType;
    private BigDecimal aiRatePerMin;
    private AiStackInfo stack;

    private EntitlementSummary entitlements;

    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AiStackInfo {
        private AiProviderInfo stt;
        private AiProviderInfo tts;
        private AiProviderInfo llm;
    }

    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AiProviderInfo {
        private String provider;
        private String model;
        private String displayName;
        private BigDecimal cost;
        private String icon;
        private String color;
        private Integer latencyMs;
    }

    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EntitlementSummary {
        private Integer maxAgents;
        private Integer maxDids;
        private Integer maxChannels;
        private List<String> features;
    }
}
