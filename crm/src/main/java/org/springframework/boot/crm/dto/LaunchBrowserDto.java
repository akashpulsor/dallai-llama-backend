package org.springframework.boot.crm.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class LaunchBrowserDto {
    private int portalId;
    private int llmId;
    private String browserSessionId;
    private String sessionId;
    private String url;
}
