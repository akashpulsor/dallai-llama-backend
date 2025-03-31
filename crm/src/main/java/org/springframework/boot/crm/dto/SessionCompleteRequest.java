package org.springframework.boot.crm.dto;

import lombok.Data;

@Data
public class SessionCompleteRequest {
    private String sessionId;
    private boolean success;
}
