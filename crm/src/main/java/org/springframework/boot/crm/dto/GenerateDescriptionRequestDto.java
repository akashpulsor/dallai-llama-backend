package org.springframework.boot.crm.dto;

import lombok.Data;

@Data
public class GenerateDescriptionRequestDto {
    private int llmId;
    private int businessId;
    private String portalUrl;
}
