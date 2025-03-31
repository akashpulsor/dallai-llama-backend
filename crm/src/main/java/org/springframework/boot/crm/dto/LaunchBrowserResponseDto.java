package org.springframework.boot.crm.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.Map;

@Data
@AllArgsConstructor
public class LaunchBrowserResponseDto {
    private String message;
    private String sessionId;
    private Map<String, String > stream;
}
