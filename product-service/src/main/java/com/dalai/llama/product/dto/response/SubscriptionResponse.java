package com.dalai.llama.product.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubscriptionResponse {

    private UUID subscriptionId;
    private String status;
    private String provisioningStatus;

    private DidDetails did;
    private SipIntegration sipIntegration;
    private ChannelDetails channels;
    private PlanDetails plan;
    private List<AppInfo> apps;
    private AdminCredentials adminCredentials;

    @Data
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

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SipIntegration {
        private String server;
        private int port;
        private String transport;
        private String username;
        private String password;
        private String realm;
        private String registrarUri;
        private int maxConcurrentCalls;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChannelDetails {
        private UUID id;
        private String direction;
        private int totalChannels;
        private Integer inboundChannels;
        private Integer outboundChannels;
        private String status;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PlanDetails {
        private String code;
        private String name;
        private String tier;
        private int includedAgents;
        private int includedMinutes;
        private Instant validUntil;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AppInfo {
        private String type;
        private String displayName;
        private String url;

        private String icon;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AdminCredentials {
        private String email;
        private String temporaryPassword;
        private String loginUrl;
    }
}