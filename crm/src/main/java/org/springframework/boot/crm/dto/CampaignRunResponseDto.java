package org.springframework.boot.crm.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class CampaignRunResponseDto {
    private int campaignRunId;

    private int businessId;

    private int campaignId;

    private boolean all;

    private int agentId;

    private String language;

    private CampaignRunEnum status;

    private int llmId;

    private int phoneId;

    private String callSId;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

}
