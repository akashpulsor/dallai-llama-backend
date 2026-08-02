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
    private final WeeklyIdeas weeklyIdeas = new WeeklyIdeas();

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

    public WeeklyIdeas getWeeklyIdeas() {
        return weeklyIdeas;
    }

    public static class Ai {
        private String provider = "openai";
        private String model = "gpt-4o-mini";
        private String apiKey = "";
        private String baseUrl = "https://api.openai.com/v1";
        private String geminiApiKey = "";
        private String geminiBaseUrl = "https://generativelanguage.googleapis.com/v1beta";
        private String geminiModel = "gemini-2.5-flash";
        private String geminiImageModel = "gemini-2.5-flash-image";
        private String geminiVideoModel = "veo-3.1-generate-preview";
        private String geminiEmbeddingModel = "text-embedding-004";
        private int geminiEmbeddingDimensions = 768;
        private String studioPolishVideoProvider = "runway";
        private boolean studioPolishPromptCompactionEnabled = true;
        private int studioPolishPromptCompactionGoogleVeoChars = 1800;
        private int studioPolishPromptCompactionLumaChars = 1800;
        private int studioPolishPromptCompactionRunwayChars = 900;
        private String lumaApiKey = "";
        private String lumaBaseUrl = "https://api.lumalabs.ai/dream-machine/v1";
        private String lumaVideoModel = "ray-flash-2";
        private String lumaVideoMode = "adhere_2";
        private long lumaVideoPollIntervalMs = 10000;
        private long lumaVideoTimeoutMs = 900000;
        private boolean providerCreditCheckEnabled = true;
        private BigDecimal lumaLowCreditThresholdUsd = BigDecimal.valueOf(10);
        private BigDecimal runwayLowCreditThresholdCredits = BigDecimal.valueOf(100);
        private String runwayApiSecret = "";
        private String runwayBaseUrl = "https://api.dev.runwayml.com";
        private String runwayApiVersion = "2024-11-06";
        private String runwayVideoModel = "aleph2";
        private long runwayVideoPollIntervalMs = 5000;
        private long runwayVideoTimeoutMs = 900000;
        private String decartApiKey = "";
        private String decartBaseUrl = "https://api.decart.ai/v1";
        private String decartVideoModel = "lucy-vton-3";
        private String decartVideoResolution = "720p";
        private boolean decartEnhancePrompt = false;
        private long decartVideoPollIntervalMs = 5000;
        private long decartVideoTimeoutMs = 900000;
        private boolean studioPolishKafkaQueueEnabled = true;
        private String studioPolishJobsTopic = "creator.studio-polish.jobs";
        private int studioPolishConsumerConcurrency = 1;
        private int studioPolishTopicPartitions = 12;
        private boolean studioPolishProviderQueueEnabled = true;
        private long studioPolishProviderQueueMaxWaitMs = 1800000;
        private long studioPolishProviderQueuePollMs = 5000;
        private int runwayMaxConcurrentGenerations = 1;
        private int runwayMaxGenerationsPerDay = 50;
        private int lumaMaxConcurrentGenerations = 10;
        private int lumaMaxGenerationsPerDay = 1000;
        private int decartMaxConcurrentGenerations = 1;
        private int decartMaxGenerationsPerDay = 100;
        private int googleVeoMaxConcurrentGenerations = 1;
        private int googleVeoMaxGenerationsPerDay = 50;
        private String googleGenaiBackend = "ai_studio";
        private String googleCloudProjectId = "";
        private String googleCloudLocation = "us-central1";
        private boolean lyriaMusicGenerationEnabled = true;
        private String lyriaMusicModel = "lyria-3-clip";
        private String ttsProvider = "google_chirp";
        private String googleTtsBaseUrl = "https://texttospeech.googleapis.com";
        private String googleTtsApiKey = "";
        private String googleTtsVoiceName = "en-US-Chirp3-HD-Charon";
        private String googleTtsVoiceMale = "en-US-Chirp3-HD-Charon";
        private String googleTtsVoiceFemale = "en-US-Chirp3-HD-Aoede";
        private String googleTtsLanguageCode = "en-US";
        private String googleTtsAudioEncoding = "MP3";
        private BigDecimal googleTtsUsdPerMillionChars = BigDecimal.valueOf(30);
        private String elevenLabsBaseUrl = "https://api.elevenlabs.io";
        private String elevenLabsApiKey = "";
        private String elevenLabsVoiceId = "";
        private String elevenLabsModel = "eleven_multilingual_v2";
        private BigDecimal elevenLabsUsdPerMillionChars = BigDecimal.valueOf(30);
        private String geminiVideoResolution = "1080p";
        private String geminiVideoDurationSeconds = "8";
        private String geminiVideoPersonGeneration = "allow_adult";
        private boolean shotVideoGenerationEnabled = true;
        private long geminiVideoPollIntervalMs = 10000;
        private long geminiVideoTimeoutMs = 900000;
        private boolean storyboardImageGenerationEnabled = true;
        private long timeoutMs = 60000;
        private Integer maxOutputTokens = 32768;
        private int geminiMaxAttempts = 3;
        private long geminiRetryBackoffMs = 1000;
        private long geminiRetryMaxDelayMs = 30000;
        private long geminiRateLimitCooldownMs = 60000;
        private long geminiRateLimitMaxCooldownMs = 300000;
        private long geminiRateLimitMaxLocalWaitMs = 5000;
        private long geminiRequestMinIntervalMs = 1000;
        private boolean audioNeuralDenoiseEnabled = true;
        private String audioNeuralDenoiseProvider = "deepfilter";
        private boolean audioNeuralDenoiseFailOnMissing = false;
        private String deepFilterCommand = "deep-filter";
        private String deepFilterModelPath = "";
        private boolean deepFilterPostFilterEnabled = true;
        private String rnnoiseCommand = "rnnoise_demo";
        private String ffmpegArnndnModelPath = "";
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

        public String getGeminiEmbeddingModel() {
            return geminiEmbeddingModel;
        }

        public void setGeminiEmbeddingModel(String geminiEmbeddingModel) {
            this.geminiEmbeddingModel = geminiEmbeddingModel;
        }

        public int getGeminiEmbeddingDimensions() {
            return geminiEmbeddingDimensions;
        }

        public void setGeminiEmbeddingDimensions(int geminiEmbeddingDimensions) {
            this.geminiEmbeddingDimensions = geminiEmbeddingDimensions;
        }

        public String getGeminiVideoModel() {
            return geminiVideoModel;
        }

        public void setGeminiVideoModel(String geminiVideoModel) {
            this.geminiVideoModel = geminiVideoModel;
        }

        public String getStudioPolishVideoProvider() {
            return studioPolishVideoProvider;
        }

        public void setStudioPolishVideoProvider(String studioPolishVideoProvider) {
            this.studioPolishVideoProvider = studioPolishVideoProvider;
        }

        public boolean isStudioPolishPromptCompactionEnabled() {
            return studioPolishPromptCompactionEnabled;
        }

        public void setStudioPolishPromptCompactionEnabled(boolean studioPolishPromptCompactionEnabled) {
            this.studioPolishPromptCompactionEnabled = studioPolishPromptCompactionEnabled;
        }

        public int getStudioPolishPromptCompactionGoogleVeoChars() {
            return studioPolishPromptCompactionGoogleVeoChars;
        }

        public void setStudioPolishPromptCompactionGoogleVeoChars(int studioPolishPromptCompactionGoogleVeoChars) {
            this.studioPolishPromptCompactionGoogleVeoChars = studioPolishPromptCompactionGoogleVeoChars;
        }

        public int getStudioPolishPromptCompactionLumaChars() {
            return studioPolishPromptCompactionLumaChars;
        }

        public void setStudioPolishPromptCompactionLumaChars(int studioPolishPromptCompactionLumaChars) {
            this.studioPolishPromptCompactionLumaChars = studioPolishPromptCompactionLumaChars;
        }

        public int getStudioPolishPromptCompactionRunwayChars() {
            return studioPolishPromptCompactionRunwayChars;
        }

        public void setStudioPolishPromptCompactionRunwayChars(int studioPolishPromptCompactionRunwayChars) {
            this.studioPolishPromptCompactionRunwayChars = studioPolishPromptCompactionRunwayChars;
        }

        public String getLumaApiKey() {
            return lumaApiKey;
        }

        public void setLumaApiKey(String lumaApiKey) {
            this.lumaApiKey = lumaApiKey;
        }

        public String getLumaBaseUrl() {
            return lumaBaseUrl;
        }

        public void setLumaBaseUrl(String lumaBaseUrl) {
            this.lumaBaseUrl = lumaBaseUrl;
        }

        public String getLumaVideoModel() {
            return lumaVideoModel;
        }

        public void setLumaVideoModel(String lumaVideoModel) {
            this.lumaVideoModel = lumaVideoModel;
        }

        public String getLumaVideoMode() {
            return lumaVideoMode;
        }

        public void setLumaVideoMode(String lumaVideoMode) {
            this.lumaVideoMode = lumaVideoMode;
        }

        public long getLumaVideoPollIntervalMs() {
            return lumaVideoPollIntervalMs;
        }

        public void setLumaVideoPollIntervalMs(long lumaVideoPollIntervalMs) {
            this.lumaVideoPollIntervalMs = lumaVideoPollIntervalMs;
        }

        public long getLumaVideoTimeoutMs() {
            return lumaVideoTimeoutMs;
        }

        public void setLumaVideoTimeoutMs(long lumaVideoTimeoutMs) {
            this.lumaVideoTimeoutMs = lumaVideoTimeoutMs;
        }

        public boolean isProviderCreditCheckEnabled() {
            return providerCreditCheckEnabled;
        }

        public void setProviderCreditCheckEnabled(boolean providerCreditCheckEnabled) {
            this.providerCreditCheckEnabled = providerCreditCheckEnabled;
        }

        public BigDecimal getLumaLowCreditThresholdUsd() {
            return lumaLowCreditThresholdUsd;
        }

        public void setLumaLowCreditThresholdUsd(BigDecimal lumaLowCreditThresholdUsd) {
            this.lumaLowCreditThresholdUsd = lumaLowCreditThresholdUsd;
        }

        public BigDecimal getRunwayLowCreditThresholdCredits() {
            return runwayLowCreditThresholdCredits;
        }

        public void setRunwayLowCreditThresholdCredits(BigDecimal runwayLowCreditThresholdCredits) {
            this.runwayLowCreditThresholdCredits = runwayLowCreditThresholdCredits;
        }

        public String getRunwayApiSecret() {
            return runwayApiSecret;
        }

        public void setRunwayApiSecret(String runwayApiSecret) {
            this.runwayApiSecret = runwayApiSecret;
        }

        public String getRunwayBaseUrl() {
            return runwayBaseUrl;
        }

        public void setRunwayBaseUrl(String runwayBaseUrl) {
            this.runwayBaseUrl = runwayBaseUrl;
        }

        public String getRunwayApiVersion() {
            return runwayApiVersion;
        }

        public void setRunwayApiVersion(String runwayApiVersion) {
            this.runwayApiVersion = runwayApiVersion;
        }

        public String getRunwayVideoModel() {
            return runwayVideoModel;
        }

        public void setRunwayVideoModel(String runwayVideoModel) {
            this.runwayVideoModel = runwayVideoModel;
        }

        public long getRunwayVideoPollIntervalMs() {
            return runwayVideoPollIntervalMs;
        }

        public void setRunwayVideoPollIntervalMs(long runwayVideoPollIntervalMs) {
            this.runwayVideoPollIntervalMs = runwayVideoPollIntervalMs;
        }

        public long getRunwayVideoTimeoutMs() {
            return runwayVideoTimeoutMs;
        }

        public void setRunwayVideoTimeoutMs(long runwayVideoTimeoutMs) {
            this.runwayVideoTimeoutMs = runwayVideoTimeoutMs;
        }

        public String getDecartApiKey() {
            return decartApiKey;
        }

        public void setDecartApiKey(String decartApiKey) {
            this.decartApiKey = decartApiKey;
        }

        public String getDecartBaseUrl() {
            return decartBaseUrl;
        }

        public void setDecartBaseUrl(String decartBaseUrl) {
            this.decartBaseUrl = decartBaseUrl;
        }

        public String getDecartVideoModel() {
            return decartVideoModel;
        }

        public void setDecartVideoModel(String decartVideoModel) {
            this.decartVideoModel = decartVideoModel;
        }

        public String getDecartVideoResolution() {
            return decartVideoResolution;
        }

        public void setDecartVideoResolution(String decartVideoResolution) {
            this.decartVideoResolution = decartVideoResolution;
        }

        public boolean isDecartEnhancePrompt() {
            return decartEnhancePrompt;
        }

        public void setDecartEnhancePrompt(boolean decartEnhancePrompt) {
            this.decartEnhancePrompt = decartEnhancePrompt;
        }

        public long getDecartVideoPollIntervalMs() {
            return decartVideoPollIntervalMs;
        }

        public void setDecartVideoPollIntervalMs(long decartVideoPollIntervalMs) {
            this.decartVideoPollIntervalMs = decartVideoPollIntervalMs;
        }

        public long getDecartVideoTimeoutMs() {
            return decartVideoTimeoutMs;
        }

        public void setDecartVideoTimeoutMs(long decartVideoTimeoutMs) {
            this.decartVideoTimeoutMs = decartVideoTimeoutMs;
        }

        public boolean isStudioPolishKafkaQueueEnabled() {
            return studioPolishKafkaQueueEnabled;
        }

        public void setStudioPolishKafkaQueueEnabled(boolean studioPolishKafkaQueueEnabled) {
            this.studioPolishKafkaQueueEnabled = studioPolishKafkaQueueEnabled;
        }

        public String getStudioPolishJobsTopic() {
            return studioPolishJobsTopic;
        }

        public void setStudioPolishJobsTopic(String studioPolishJobsTopic) {
            this.studioPolishJobsTopic = studioPolishJobsTopic;
        }

        public int getStudioPolishConsumerConcurrency() {
            return studioPolishConsumerConcurrency;
        }

        public void setStudioPolishConsumerConcurrency(int studioPolishConsumerConcurrency) {
            this.studioPolishConsumerConcurrency = studioPolishConsumerConcurrency;
        }

        public int getStudioPolishTopicPartitions() {
            return studioPolishTopicPartitions;
        }

        public void setStudioPolishTopicPartitions(int studioPolishTopicPartitions) {
            this.studioPolishTopicPartitions = studioPolishTopicPartitions;
        }

        public boolean isStudioPolishProviderQueueEnabled() {
            return studioPolishProviderQueueEnabled;
        }

        public void setStudioPolishProviderQueueEnabled(boolean studioPolishProviderQueueEnabled) {
            this.studioPolishProviderQueueEnabled = studioPolishProviderQueueEnabled;
        }

        public long getStudioPolishProviderQueueMaxWaitMs() {
            return studioPolishProviderQueueMaxWaitMs;
        }

        public void setStudioPolishProviderQueueMaxWaitMs(long studioPolishProviderQueueMaxWaitMs) {
            this.studioPolishProviderQueueMaxWaitMs = studioPolishProviderQueueMaxWaitMs;
        }

        public long getStudioPolishProviderQueuePollMs() {
            return studioPolishProviderQueuePollMs;
        }

        public void setStudioPolishProviderQueuePollMs(long studioPolishProviderQueuePollMs) {
            this.studioPolishProviderQueuePollMs = studioPolishProviderQueuePollMs;
        }

        public int getRunwayMaxConcurrentGenerations() {
            return runwayMaxConcurrentGenerations;
        }

        public void setRunwayMaxConcurrentGenerations(int runwayMaxConcurrentGenerations) {
            this.runwayMaxConcurrentGenerations = runwayMaxConcurrentGenerations;
        }

        public int getRunwayMaxGenerationsPerDay() {
            return runwayMaxGenerationsPerDay;
        }

        public void setRunwayMaxGenerationsPerDay(int runwayMaxGenerationsPerDay) {
            this.runwayMaxGenerationsPerDay = runwayMaxGenerationsPerDay;
        }

        public int getLumaMaxConcurrentGenerations() {
            return lumaMaxConcurrentGenerations;
        }

        public void setLumaMaxConcurrentGenerations(int lumaMaxConcurrentGenerations) {
            this.lumaMaxConcurrentGenerations = lumaMaxConcurrentGenerations;
        }

        public int getLumaMaxGenerationsPerDay() {
            return lumaMaxGenerationsPerDay;
        }

        public void setLumaMaxGenerationsPerDay(int lumaMaxGenerationsPerDay) {
            this.lumaMaxGenerationsPerDay = lumaMaxGenerationsPerDay;
        }

        public int getDecartMaxConcurrentGenerations() {
            return decartMaxConcurrentGenerations;
        }

        public void setDecartMaxConcurrentGenerations(int decartMaxConcurrentGenerations) {
            this.decartMaxConcurrentGenerations = decartMaxConcurrentGenerations;
        }

        public int getDecartMaxGenerationsPerDay() {
            return decartMaxGenerationsPerDay;
        }

        public void setDecartMaxGenerationsPerDay(int decartMaxGenerationsPerDay) {
            this.decartMaxGenerationsPerDay = decartMaxGenerationsPerDay;
        }

        public int getGoogleVeoMaxConcurrentGenerations() {
            return googleVeoMaxConcurrentGenerations;
        }

        public void setGoogleVeoMaxConcurrentGenerations(int googleVeoMaxConcurrentGenerations) {
            this.googleVeoMaxConcurrentGenerations = googleVeoMaxConcurrentGenerations;
        }

        public int getGoogleVeoMaxGenerationsPerDay() {
            return googleVeoMaxGenerationsPerDay;
        }

        public void setGoogleVeoMaxGenerationsPerDay(int googleVeoMaxGenerationsPerDay) {
            this.googleVeoMaxGenerationsPerDay = googleVeoMaxGenerationsPerDay;
        }

        public String getGoogleGenaiBackend() {
            return googleGenaiBackend;
        }

        public void setGoogleGenaiBackend(String googleGenaiBackend) {
            this.googleGenaiBackend = googleGenaiBackend;
        }

        public String getGoogleCloudProjectId() {
            return googleCloudProjectId;
        }

        public void setGoogleCloudProjectId(String googleCloudProjectId) {
            this.googleCloudProjectId = googleCloudProjectId;
        }

        public String getGoogleCloudLocation() {
            return googleCloudLocation;
        }

        public void setGoogleCloudLocation(String googleCloudLocation) {
            this.googleCloudLocation = googleCloudLocation;
        }

        public boolean isLyriaMusicGenerationEnabled() {
            return lyriaMusicGenerationEnabled;
        }

        public void setLyriaMusicGenerationEnabled(boolean lyriaMusicGenerationEnabled) {
            this.lyriaMusicGenerationEnabled = lyriaMusicGenerationEnabled;
        }

        public String getLyriaMusicModel() {
            return lyriaMusicModel;
        }

        public void setLyriaMusicModel(String lyriaMusicModel) {
            this.lyriaMusicModel = lyriaMusicModel;
        }

        public String getTtsProvider() {
            return ttsProvider;
        }

        public void setTtsProvider(String ttsProvider) {
            this.ttsProvider = ttsProvider;
        }

        public String getGoogleTtsBaseUrl() {
            return googleTtsBaseUrl;
        }

        public void setGoogleTtsBaseUrl(String googleTtsBaseUrl) {
            this.googleTtsBaseUrl = googleTtsBaseUrl;
        }

        public String getGoogleTtsApiKey() {
            return googleTtsApiKey;
        }

        public void setGoogleTtsApiKey(String googleTtsApiKey) {
            this.googleTtsApiKey = googleTtsApiKey;
        }

        public String getGoogleTtsVoiceName() {
            return googleTtsVoiceName;
        }

        public void setGoogleTtsVoiceName(String googleTtsVoiceName) {
            this.googleTtsVoiceName = googleTtsVoiceName;
        }

        public String getGoogleTtsVoiceMale() {
            return googleTtsVoiceMale;
        }

        public void setGoogleTtsVoiceMale(String googleTtsVoiceMale) {
            this.googleTtsVoiceMale = googleTtsVoiceMale;
        }

        public String getGoogleTtsVoiceFemale() {
            return googleTtsVoiceFemale;
        }

        public void setGoogleTtsVoiceFemale(String googleTtsVoiceFemale) {
            this.googleTtsVoiceFemale = googleTtsVoiceFemale;
        }

        public String getGoogleTtsLanguageCode() {
            return googleTtsLanguageCode;
        }

        public void setGoogleTtsLanguageCode(String googleTtsLanguageCode) {
            this.googleTtsLanguageCode = googleTtsLanguageCode;
        }

        public String getGoogleTtsAudioEncoding() {
            return googleTtsAudioEncoding;
        }

        public void setGoogleTtsAudioEncoding(String googleTtsAudioEncoding) {
            this.googleTtsAudioEncoding = googleTtsAudioEncoding;
        }

        public BigDecimal getGoogleTtsUsdPerMillionChars() {
            return googleTtsUsdPerMillionChars;
        }

        public void setGoogleTtsUsdPerMillionChars(BigDecimal googleTtsUsdPerMillionChars) {
            this.googleTtsUsdPerMillionChars = googleTtsUsdPerMillionChars;
        }

        public String getElevenLabsBaseUrl() {
            return elevenLabsBaseUrl;
        }

        public void setElevenLabsBaseUrl(String elevenLabsBaseUrl) {
            this.elevenLabsBaseUrl = elevenLabsBaseUrl;
        }

        public String getElevenLabsApiKey() {
            return elevenLabsApiKey;
        }

        public void setElevenLabsApiKey(String elevenLabsApiKey) {
            this.elevenLabsApiKey = elevenLabsApiKey;
        }

        public String getElevenLabsVoiceId() {
            return elevenLabsVoiceId;
        }

        public void setElevenLabsVoiceId(String elevenLabsVoiceId) {
            this.elevenLabsVoiceId = elevenLabsVoiceId;
        }

        public String getElevenLabsModel() {
            return elevenLabsModel;
        }

        public void setElevenLabsModel(String elevenLabsModel) {
            this.elevenLabsModel = elevenLabsModel;
        }

        public BigDecimal getElevenLabsUsdPerMillionChars() {
            return elevenLabsUsdPerMillionChars;
        }

        public void setElevenLabsUsdPerMillionChars(BigDecimal elevenLabsUsdPerMillionChars) {
            this.elevenLabsUsdPerMillionChars = elevenLabsUsdPerMillionChars;
        }

        public String getGeminiVideoResolution() {
            return geminiVideoResolution;
        }

        public void setGeminiVideoResolution(String geminiVideoResolution) {
            this.geminiVideoResolution = geminiVideoResolution;
        }

        public String getGeminiVideoDurationSeconds() {
            return geminiVideoDurationSeconds;
        }

        public void setGeminiVideoDurationSeconds(String geminiVideoDurationSeconds) {
            this.geminiVideoDurationSeconds = geminiVideoDurationSeconds;
        }

        public String getGeminiVideoPersonGeneration() {
            return geminiVideoPersonGeneration;
        }

        public void setGeminiVideoPersonGeneration(String geminiVideoPersonGeneration) {
            this.geminiVideoPersonGeneration = geminiVideoPersonGeneration;
        }

        public boolean isShotVideoGenerationEnabled() {
            return shotVideoGenerationEnabled;
        }

        public void setShotVideoGenerationEnabled(boolean shotVideoGenerationEnabled) {
            this.shotVideoGenerationEnabled = shotVideoGenerationEnabled;
        }

        public long getGeminiVideoPollIntervalMs() {
            return geminiVideoPollIntervalMs;
        }

        public void setGeminiVideoPollIntervalMs(long geminiVideoPollIntervalMs) {
            this.geminiVideoPollIntervalMs = geminiVideoPollIntervalMs;
        }

        public long getGeminiVideoTimeoutMs() {
            return geminiVideoTimeoutMs;
        }

        public void setGeminiVideoTimeoutMs(long geminiVideoTimeoutMs) {
            this.geminiVideoTimeoutMs = geminiVideoTimeoutMs;
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

        public int getGeminiMaxAttempts() {
            return geminiMaxAttempts;
        }

        public void setGeminiMaxAttempts(int geminiMaxAttempts) {
            this.geminiMaxAttempts = geminiMaxAttempts;
        }

        public long getGeminiRetryBackoffMs() {
            return geminiRetryBackoffMs;
        }

        public void setGeminiRetryBackoffMs(long geminiRetryBackoffMs) {
            this.geminiRetryBackoffMs = geminiRetryBackoffMs;
        }

        public long getGeminiRetryMaxDelayMs() {
            return geminiRetryMaxDelayMs;
        }

        public void setGeminiRetryMaxDelayMs(long geminiRetryMaxDelayMs) {
            this.geminiRetryMaxDelayMs = geminiRetryMaxDelayMs;
        }

        public long getGeminiRateLimitCooldownMs() {
            return geminiRateLimitCooldownMs;
        }

        public void setGeminiRateLimitCooldownMs(long geminiRateLimitCooldownMs) {
            this.geminiRateLimitCooldownMs = geminiRateLimitCooldownMs;
        }

        public long getGeminiRateLimitMaxCooldownMs() {
            return geminiRateLimitMaxCooldownMs;
        }

        public void setGeminiRateLimitMaxCooldownMs(long geminiRateLimitMaxCooldownMs) {
            this.geminiRateLimitMaxCooldownMs = geminiRateLimitMaxCooldownMs;
        }

        public long getGeminiRateLimitMaxLocalWaitMs() {
            return geminiRateLimitMaxLocalWaitMs;
        }

        public void setGeminiRateLimitMaxLocalWaitMs(long geminiRateLimitMaxLocalWaitMs) {
            this.geminiRateLimitMaxLocalWaitMs = geminiRateLimitMaxLocalWaitMs;
        }

        public long getGeminiRequestMinIntervalMs() {
            return geminiRequestMinIntervalMs;
        }

        public void setGeminiRequestMinIntervalMs(long geminiRequestMinIntervalMs) {
            this.geminiRequestMinIntervalMs = geminiRequestMinIntervalMs;
        }

        public boolean isAudioNeuralDenoiseEnabled() {
            return audioNeuralDenoiseEnabled;
        }

        public void setAudioNeuralDenoiseEnabled(boolean audioNeuralDenoiseEnabled) {
            this.audioNeuralDenoiseEnabled = audioNeuralDenoiseEnabled;
        }

        public String getAudioNeuralDenoiseProvider() {
            return audioNeuralDenoiseProvider;
        }

        public void setAudioNeuralDenoiseProvider(String audioNeuralDenoiseProvider) {
            this.audioNeuralDenoiseProvider = audioNeuralDenoiseProvider;
        }

        public boolean isAudioNeuralDenoiseFailOnMissing() {
            return audioNeuralDenoiseFailOnMissing;
        }

        public void setAudioNeuralDenoiseFailOnMissing(boolean audioNeuralDenoiseFailOnMissing) {
            this.audioNeuralDenoiseFailOnMissing = audioNeuralDenoiseFailOnMissing;
        }

        public String getDeepFilterCommand() {
            return deepFilterCommand;
        }

        public void setDeepFilterCommand(String deepFilterCommand) {
            this.deepFilterCommand = deepFilterCommand;
        }

        public String getDeepFilterModelPath() {
            return deepFilterModelPath;
        }

        public void setDeepFilterModelPath(String deepFilterModelPath) {
            this.deepFilterModelPath = deepFilterModelPath;
        }

        public boolean isDeepFilterPostFilterEnabled() {
            return deepFilterPostFilterEnabled;
        }

        public void setDeepFilterPostFilterEnabled(boolean deepFilterPostFilterEnabled) {
            this.deepFilterPostFilterEnabled = deepFilterPostFilterEnabled;
        }

        public String getRnnoiseCommand() {
            return rnnoiseCommand;
        }

        public void setRnnoiseCommand(String rnnoiseCommand) {
            this.rnnoiseCommand = rnnoiseCommand;
        }

        public String getFfmpegArnndnModelPath() {
            return ffmpegArnndnModelPath;
        }

        public void setFfmpegArnndnModelPath(String ffmpegArnndnModelPath) {
            this.ffmpegArnndnModelPath = ffmpegArnndnModelPath;
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
        private BigDecimal audioEnhancementRatePerMinuteUsd = new BigDecimal("0.02");
        private BigDecimal usageMarkupPercent = new BigDecimal("85");
        private BigDecimal videoUsageMarkupPercent = new BigDecimal("20");

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

        public BigDecimal getAudioEnhancementRatePerMinuteUsd() {
            return audioEnhancementRatePerMinuteUsd;
        }

        public void setAudioEnhancementRatePerMinuteUsd(BigDecimal audioEnhancementRatePerMinuteUsd) {
            this.audioEnhancementRatePerMinuteUsd = audioEnhancementRatePerMinuteUsd;
        }

        public BigDecimal getUsageMarkupPercent() {
            return usageMarkupPercent;
        }

        public void setUsageMarkupPercent(BigDecimal usageMarkupPercent) {
            this.usageMarkupPercent = usageMarkupPercent;
        }

        public BigDecimal getVideoUsageMarkupPercent() {
            return videoUsageMarkupPercent;
        }

        public void setVideoUsageMarkupPercent(BigDecimal videoUsageMarkupPercent) {
            this.videoUsageMarkupPercent = videoUsageMarkupPercent;
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
            private int retryCount = 2;
            private long retryBackoffMs = 60000;

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

            public int getRetryCount() {
                return retryCount;
            }

            public void setRetryCount(int retryCount) {
                this.retryCount = retryCount;
            }

            public long getRetryBackoffMs() {
                return retryBackoffMs;
            }

            public void setRetryBackoffMs(long retryBackoffMs) {
                this.retryBackoffMs = retryBackoffMs;
            }
        }
    }

    public static class WeeklyIdeas {
        private final Scheduler scheduler = new Scheduler();

        public Scheduler getScheduler() {
            return scheduler;
        }

        public static class Scheduler {
            private boolean enabled = true;
            private long fixedDelayMs = 1296000000;
            private long initialDelayMs = 60000;
            private int retryCount = 2;
            private long retryBackoffMs = 60000;

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

            public int getRetryCount() {
                return retryCount;
            }

            public void setRetryCount(int retryCount) {
                this.retryCount = retryCount;
            }

            public long getRetryBackoffMs() {
                return retryBackoffMs;
            }

            public void setRetryBackoffMs(long retryBackoffMs) {
                this.retryBackoffMs = retryBackoffMs;
            }
        }
    }
}
