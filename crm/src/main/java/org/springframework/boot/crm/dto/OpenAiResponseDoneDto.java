package org.springframework.boot.crm.dto;


import com.fasterxml.jackson.annotation.JsonProperty;
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
        @JsonProperty("total_tokens")
        private int totalTokens;

        @JsonProperty("input_tokens")
        private int inputTokens;

        @JsonProperty("output_tokens")
        private int outputTokens;

        @JsonProperty("input_token_details")
        private InputTokenDetails inputTokenDetails;

        @JsonProperty("output_token_details")
        private OutputTokenDetails outputTokenDetails;
    }

    @Data
    public static class InputTokenDetails {
        @JsonProperty("text_tokens")
        private int textTokens;

        @JsonProperty("audio_tokens")
        private int audioTokens;

        @JsonProperty("cached_tokens")
        private int cachedTokens;

        @JsonProperty("cached_tokens_details")
        private CachedTokensDetails cachedTokensDetails;
    }

    @Data
    public static class OutputTokenDetails {

        @JsonProperty("text_tokens")
        private int textTokens;

        @JsonProperty("audio_tokens")
        private int audioTokens;

    }

    @Data
    public static class CachedTokensDetails {
        @JsonProperty("text_tokens")
        private int textTokens;

        @JsonProperty("audio_tokens")
        private int audioTokens;
    }
}
