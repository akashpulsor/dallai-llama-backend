package org.springframework.boot.crm.dto;


import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class OpenAiResponseDoneDto {
    private String type;
    private String event_id;
    private Response response;

    @Data
    public static class Response {
        private String object;
        private String id;
        private String status;
        private String statusDetails;
        private List<OutputItem> output;
        private String conversationId;
        private List<String> modalities;
        private String voice;
        private String outputAudioFormat;
        private double temperature;
        private String maxOutputTokens;
        private Usage usage;
        private Map<String, Object> metadata;
    }

    @Data
    public static class OutputItem {
        private String id;
        private String object;
        private String type;
        private String status;
        private String role;
        private List<Content> content;
    }

    @Data
    public static class Content {
        private String type;
        private String transcript;
    }

    @Data
    public static class Usage {
        private int totalTokens;
        private int inputTokens;
        private int outputTokens;
        private InputTokenDetails inputTokenDetails;
        private OutputTokenDetails outputTokenDetails;
    }

    @Data
    public static class InputTokenDetails {
        private int textTokens;
        private int audioTokens;
        private int cachedTokens;
        private CachedTokensDetails cachedTokensDetails;
    }

    @Data
    public static class OutputTokenDetails {
        private int textTokens;
        private int audioTokens;

    }

    @Data
    public static class CachedTokensDetails {
        private int textTokens;
        private int audioTokens;
    }
}
