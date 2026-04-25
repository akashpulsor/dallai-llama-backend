package com.dalai.llama.product.dto.response;

import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;



@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubscriptionResponse {

    private UUID subscriptionId;
    private String status;
    private String provisioningStatus;
    private UUID tenantAppId;

    // payment details
    private BigDecimal requiredAmount;
    private UUID paymentId;
    private String gatewayOrderId;
    private String currency;

    private BigDecimal currentWalletBalance;
    private BigDecimal shortFallAmount;
    // preview / provisioned resources
    private PlanDetails plan;
    private DidDetails did;

    private SipIntegration sipIntegration;
    private ChannelDetails channels;
    private List<AppInfo> apps;
    private AdminCredentials adminCredentials;

    // ==================== NESTED DTOs ====================

    // ==================== SIP INTEGRATION ====================

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SipIntegration {
        private String server;
        private Integer port;
        private String transport;
        private String username;
        private String password;
        private String realm;
        private String registrarUri;
        private Integer maxConcurrentCalls;
    }


// ==================== CHANNEL DETAILS ====================

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChannelDetails {
        private UUID id;
        private String direction;
        private Integer totalChannels;
        private Integer inboundChannels;
        private Integer outboundChannels;
        private String status;
    }


// ==================== APP INFO ====================

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AppInfo {
        private String type;
        private String displayName;
        private String url;
        private String icon;
    }


// ==================== ADMIN CREDENTIALS ====================

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AdminCredentials {
        private String email;
        private String temporaryPassword;
        private String loginUrl;
    }

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PlanDetails {
        private String code;
        private String name;
        private String tier;
        private Integer includedAgents;
        private Integer includedMinutes;
        private Instant validUntil;
    }

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DidDetails {
        private UUID id;
        private String number;
        private String displayNumber;
        private String country;
        private String region;
        private String city;
        private String status;
    }
}