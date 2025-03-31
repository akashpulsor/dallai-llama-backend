package org.springframework.boot.crm.dto;

import lombok.Data;

import java.util.Map;

@Data
public class BrowserSessionResponse {
    private String sessionId;
    private Long portalId;
    private String currentUrl;
    private String status;
    private Map<String, Object> metadata;
}
