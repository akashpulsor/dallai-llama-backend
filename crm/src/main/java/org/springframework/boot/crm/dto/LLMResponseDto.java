package org.springframework.boot.crm.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
public class LLMResponseDto {
    private String type;
    private List<ActionDto> data;

    @Override
    public String toString() {
        return "LLMResponseDto{" +
                "type='" + type + '\'' +
                ", action=" + data +
                '}';
    }

    @Data
    public static class ActionDto {

        private String action;
        private String description;
        private String code;
        private String reason;

    }
}
