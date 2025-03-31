package org.springframework.boot.crm.dto;

import lombok.Data;

import java.util.Map;

@Data
public class BrowserSessionCreateRequest {
    private int LlmId;
    private int portalId;
    private int campaignId;
    private int businessId;
}
