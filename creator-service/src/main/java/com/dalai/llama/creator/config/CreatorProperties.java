package com.dalai.llama.creator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "creator")
public class CreatorProperties {

    private final Ai ai = new Ai();
    private final Jobs jobs = new Jobs();
    private final Kafka kafka = new Kafka();
    private final Storage storage = new Storage();
    private final Services services = new Services();
    private final Trends trends = new Trends();

    public Ai getAi() {
        return ai;
    }

    public Jobs getJobs() {
        return jobs;
    }

    public Kafka getKafka() {
        return kafka;
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
        private String provider = "mock";
        private String model = "mock-creator-v1";

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

    public static class Storage {
        private String endpoint = "http://localhost:9000";
        private String accessKey = "minioadmin";
        private String secretKey = "minioadmin";
        private String creatorAssetsBucket = "creator-assets";
        private String creatorExportsBucket = "creator-exports";
        private String publicUrl = "http://localhost:9000";

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
