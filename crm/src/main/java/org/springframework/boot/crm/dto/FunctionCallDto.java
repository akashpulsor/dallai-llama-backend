package org.springframework.boot.crm.dto;

import lombok.Data;

@Data
public class FunctionCallDto {
    private String type;
    private String messageId;
    private FunctionCall functionCall;

    @Data
    public static class FunctionCall {
        private String name;
        private String arguments;
    }
}
