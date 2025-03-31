package org.springframework.boot.crm.dto;

import lombok.Data;

import java.util.Map;

@Data
public class SessionInitRequest {
    private int userId;
    private  int llmId;
    private Long portalId;
    private int intentId;
    private String campaignId;
    private String startUrl;
    private Map<String, Object> metadata;

}
