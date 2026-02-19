package com.dalai.llama.product.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubscriptionRequest {

    @NotBlank(message = "Product code is required")
    private String productCode;

    @NotBlank(message = "Plan code is required")
    private String planCode;

    @NotNull(message = "Tenant ID is required")
    private UUID tenantId;

    @NotNull(message = "DID information is required")
    @Valid
    private DidInfo did;

    @Min(value = 1, message = "Agent count must be at least 1")
    private Integer agentCount;

    private ChannelConfig channelConfig;

    @Builder.Default
    private String paymentMethod = "WALLET";

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DidInfo {
        @NotBlank(message = "DID number is required")
        private String number;
        private String country;
        private String region;
        private String city;
        private BigDecimal monthlyFee;
        private BigDecimal setupFee;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChannelConfig {
        private Integer totalChannels;
        private Integer inboundChannels;
        private Integer outboundChannels;
    }
}