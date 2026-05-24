package com.dalai.llama.creator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

@ConfigurationProperties(prefix = "creator")
public class CreatorProperties {

    private final Ai ai = new Ai();
    private final Billing billing = new Billing();
    private final Jobs jobs = new Jobs();
    private final Kafka kafka = new Kafka();
    private final Connectors connectors = new Connectors();
    private final Storage storage = new Storage();
    private final Services services = new Services();
    private final Trends trends = new Trends();

    public Ai getAi() {
        return ai;
    }

    public Billing getBilling() {
        return billing;
    }

    public Jobs getJobs() {
        return jobs;
    }

    public Kafka getKafka() {
        return kafka;
    }

    public Connectors getConnectors() {
        return connectors;
    }

    public Storage getStorage() {
        return storage;
    }

    public Services getServices() {
        return services;
    }

    public Trends getTrends() {
        return trends;
    }

    public static class Ai {
        private String provider = "openai";
        private String model = "gpt-4o-mini";
        private String apiKey = "";
        private String baseUrl = "https://api.openai.com/v1";
        private String geminiApiKey = "";
        private String geminiBaseUrl = "https://generativelanguage.googleapis.com/v1beta";
        private String geminiModel = "gemini-2.5-flash";
        private String geminiImageModel = "gemini-3.1-flash-image-preview";
        private boolean storyboardImageGenerationEnabled = true;
        private long timeoutMs = 60000;
        private Integer maxOutputTokens = 32768;
        private final AiBilling billing = new AiBilling();

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getGeminiApiKey() {
            return geminiApiKey;
        }

        public void setGeminiApiKey(String geminiApiKey) {
            this.geminiApiKey = geminiApiKey;
        }

        public String getGeminiBaseUrl() {
            return geminiBaseUrl;
        }

        public void setGeminiBaseUrl(String geminiBaseUrl) {
            this.geminiBaseUrl = geminiBaseUrl;
        }

        public String getGeminiModel() {
            return geminiModel;
        }

        public void setGeminiModel(String geminiModel) {
            this.geminiModel = geminiModel;
        }

        public String getGeminiImageModel() {
            return geminiImageModel;
        }

        public void setGeminiImageModel(String geminiImageModel) {
            this.geminiImageModel = geminiImageModel;
        }

        public boolean isStoryboardImageGenerationEnabled() {
            return storyboardImageGenerationEnabled;
        }

        public void setStoryboardImageGenerationEnabled(boolean storyboardImageGenerationEnabled) {
            this.storyboardImageGenerationEnabled = storyboardImageGenerationEnabled;
        }

        public long getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(long timeoutMs) {
            this.timeoutMs = timeoutMs;
        }

        public Integer getMaxOutputTokens() {
            return maxOutputTokens;
        }

        public void setMaxOutputTokens(Integer maxOutputTokens) {
            this.maxOutputTokens = maxOutputTokens;
        }

        public AiBilling getBilling() {
            return billing;
        }
    }

    public static class AiBilling {
        private boolean enabled = true;
        private BigDecimal tokenRate = new BigDecimal("0.0001");
        private BigDecimal minimumCharge = BigDecimal.ZERO;
        private String currency = "INR";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public BigDecimal getTokenRate() {
            return tokenRate;
        }

        public void setTokenRate(BigDecimal tokenRate) {
            this.tokenRate = tokenRate;
        }

        public BigDecimal getMinimumCharge() {
            return minimumCharge;
        }

        public void setMinimumCharge(BigDecimal minimumCharge) {
            this.minimumCharge = minimumCharge;
        }

        public String getCurrency() {
            return currency;
        }

        public void setCurrency(String currency) {
            this.currency = currency;
        }
    }

    public static class Billing {
        private boolean walletGuardEnabled = true;
        private BigDecimal minimumWalletBalance = BigDecimal.valueOf(100);
        private long walletCheckTimeoutMs = 3000;

        public boolean isWalletGuardEnabled() {
            return walletGuardEnabled;
        }

        public void setWalletGuardEnabled(boolean walletGuardEnabled) {
            this.walletGuardEnabled = walletGuardEnabled;
        }

        public BigDecimal getMinimumWalletBalance() {
            return minimumWalletBalance;
        }

        public void setMinimumWalletBalance(BigDecimal minimumWalletBalance) {
            this.minimumWalletBalance = minimumWalletBalance;
        }

        public long getWalletCheckTimeoutMs() {
            return walletCheckTimeoutMs;
        }

        public void setWalletCheckTimeoutMs(long walletCheckTimeoutMs) {
            this.walletCheckTimeoutMs = walletCheckTimeoutMs;
        }
    }

    public static class Jobs {
        private long ttlSeconds = 86400;

        public long getTtlSeconds() {
            return ttlSeconds;
        }

        public void setTtlSeconds(long ttlSeconds) {
            this.ttlSeconds = ttlSeconds;
        }
    }

    public static class Kafka {
        private String generationJobsTopic = "creator.generation.jobs";
        private String billingEventsTopic = "creator.billing.events";
        private String analyticsEventsTopic = "creator.analytics.events";

        public String getGenerationJobsTopic() {
            return generationJobsTopic;
        }

        public void setGenerationJobsTopic(String generationJobsTopic) {
            this.generationJobsTopic = generationJobsTopic;
        }

        public String getBillingEventsTopic() {
            return billingEventsTopic;
        }

        public void setBillingEventsTopic(String billingEventsTopic) {
            this.billingEventsTopic = billingEventsTopic;
        }

        public String getAnalyticsEventsTopic() {
            return analyticsEventsTopic;
        }

        public void setAnalyticsEventsTopic(String analyticsEventsTopic) {
            this.analyticsEventsTopic = analyticsEventsTopic;
        }
    }

    public static class Connectors {
        private final Reddit reddit = new Reddit();

        public Reddit getReddit() {
            return reddit;
        }
    }

    public static class Reddit {
        private String clientId = "";
        private String clientSecret = "";
        private String userAgent = "DalaiLlamaCreatorBot/1.0";
        private String tokenUrl = "https://www.reddit.com/api/v1/access_token";
        private String apiBaseUrl = "https://oauth.reddit.com";
        private long tokenRefreshSkewSeconds = 60;

        public String getClientId() {
            return clientId;
        }

        public void setClientId(String clientId) {
            this.clientId = clientId;
        }

        public String getClientSecret() {
            return clientSecret;
        }

        public void setClientSecret(String clientSecret) {
            this.clientSecret = clientSecret;
        }

        public String getUserAgent() {
            return userAgent;
        }

        public void setUserAgent(String userAgent) {
            this.userAgent = userAgent;
        }

        public String getTokenUrl() {
            return tokenUrl;
        }

        public void setTokenUrl(String tokenUrl) {
            this.tokenUrl = tokenUrl;
        }

        public String getApiBaseUrl() {
            return apiBaseUrl;
        }

        public void setApiBaseUrl(String apiBaseUrl) {
            this.apiBaseUrl = apiBaseUrl;
        }

        public long getTokenRefreshSkewSeconds() {
            return tokenRefreshSkewSeconds;
        }

        public void setTokenRefreshSkewSeconds(long tokenRefreshSkewSeconds) {
            this.tokenRefreshSkewSeconds = tokenRefreshSkewSeconds;
        }

        public boolean hasCredentials() {
            return clientId != null && !clientId.isBlank()
                    && clientSecret != null && !clientSecret.isBlank();
        }
    }

    public static class Storage {
        private String endpoint = "http://localhost:9000";
        private String accessKey = "minioadmin";
        private String secretKey = "minioadmin";
        private String creatorAssetsBucket = "creator-assets";
        private String creatorExportsBucket = "creator-exports";
        private String publicUrl = "http://localhost:9000";
        private long signedUrlTtlSeconds = 3600;

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public String getAccessKey() {
            return accessKey;
        }

        public void setAccessKey(String accessKey) {
            this.accessKey = accessKey;
        }

        public String getSecretKey() {
            return secretKey;
        }

        public void setSecretKey(String secretKey) {
            this.secretKey = secretKey;
        }

        public String getCreatorAssetsBucket() {
            return creatorAssetsBucket;
        }

        public void setCreatorAssetsBucket(String creatorAssetsBucket) {
            this.creatorAssetsBucket = creatorAssetsBucket;
        }

        public String getCreatorExportsBucket() {
            return creatorExportsBucket;
        }

        public void setCreatorExportsBucket(String creatorExportsBucket) {
            this.creatorExportsBucket = creatorExportsBucket;
        }

        public String getPublicUrl() {
            return publicUrl;
        }

        public void setPublicUrl(String publicUrl) {
            this.publicUrl = publicUrl;
        }

        public long getSignedUrlTtlSeconds() {
            return signedUrlTtlSeconds;
        }

        public void setSignedUrlTtlSeconds(long signedUrlTtlSeconds) {
            this.signedUrlTtlSeconds = signedUrlTtlSeconds;
        }
    }

    public static class Services {
        private String tenantUrl = "http://localhost:8081";
        private String productUrl = "http://localhost:8082";
        private String billingUrl = "http://localhost:8083";

        public String getTenantUrl() {
            return tenantUrl;
        }

        public void setTenantUrl(String tenantUrl) {
            this.tenantUrl = tenantUrl;
        }

        public String getProductUrl() {
            return productUrl;
        }

        public void setProductUrl(String productUrl) {
            this.productUrl = productUrl;
        }

        public String getBillingUrl() {
            return billingUrl;
        }

        public void setBillingUrl(String billingUrl) {
            this.billingUrl = billingUrl;
        }
    }

    public static class Trends {
        private final Scheduler scheduler = new Scheduler();

        public Scheduler getScheduler() {
            return scheduler;
        }

        public static class Scheduler {
            private boolean enabled = false;
            private long fixedDelayMs = 1800000;
            private long initialDelayMs = 60000;
            private long windowMinutes = 30;
            private String countryCode = "IN";

            public boolean isEnabled() {
                return enabled;
            }

            public void setEnabled(boolean enabled) {
                this.enabled = enabled;
            }

            public long getFixedDelayMs() {
                return fixedDelayMs;
            }

            public void setFixedDelayMs(long fixedDelayMs) {
                this.fixedDelayMs = fixedDelayMs;
            }

            public long getInitialDelayMs() {
                return initialDelayMs;
            }

            public void setInitialDelayMs(long initialDelayMs) {
                this.initialDelayMs = initialDelayMs;
            }

            public long getWindowMinutes() {
                return windowMinutes;
            }

            public void setWindowMinutes(long windowMinutes) {
                this.windowMinutes = windowMinutes;
            }

            public String getCountryCode() {
                return countryCode;
            }

            public void setCountryCode(String countryCode) {
                this.countryCode = countryCode;
            }
        }
    }
}
