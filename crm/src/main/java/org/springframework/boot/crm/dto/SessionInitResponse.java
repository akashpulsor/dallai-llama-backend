package org.springframework.boot.crm.dto;

import lombok.Data;

import java.util.Map;

@Data
public class SessionInitResponse {
    private String sessionId;
    private String startUrl;
    private Map<String, String> initialContext;
}
