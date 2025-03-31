package org.springframework.boot.crm.dto;

import lombok.Data;

@Data
public class DomContextRequest {
    private String domData;
    private int portalId;
    private int intentId;
    private int llmId;
    private int businessId;
    private int userId;
}
