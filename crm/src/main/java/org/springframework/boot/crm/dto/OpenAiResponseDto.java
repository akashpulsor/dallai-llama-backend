package org.springframework.boot.crm.dto;

import lombok.Data;

import java.util.List;

@Data
public class OpenAiResponseDto {
    private String type;
    private String event_id;
    private OpenAiSession session;

    @Data
    private static  class OpenAiSession {
        private String id;
        private String object;
        private String model;
        private int expires_at;
        private List<String> modalities;
        private String instructions;
        private String voice;
        private DetectionDto turn_detection;
        private String input_audio_format;
        private String output_audio_format;
        private String input_audio_transcription;
        private String tool_choice;
        private double temperature;
        private String max_response_output_tokens;
    }

    @Data
    private static class DetectionDto {
        private String type;
        private double threshold;
        private int prefix_padding_ms;
        private int suffix_padding_ms;
    }
}
