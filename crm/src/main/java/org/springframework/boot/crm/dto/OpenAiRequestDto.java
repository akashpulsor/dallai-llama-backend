package org.springframework.boot.crm.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class OpenAiRequestDto {
    private String event_id;
    private String type;
    private OpenAISession session;

    @Data
    public static class OpenAISession{
        private List<String> modalities;
        private String instructions;
        private String voice;
        private String input_audio_format;
        private String output_audio_format;
        private String input_audio_transcription;
        private Map<String, String> turn_detection;
        private List<String> tools;
        private String tool_choice;
        private double temperature;

    }
}
