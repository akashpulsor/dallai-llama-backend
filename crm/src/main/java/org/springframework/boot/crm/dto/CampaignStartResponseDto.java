package org.springframework.boot.crm.dto;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;

import java.time.LocalDateTime;
import java.util.Set;

@Data
public class CampaignStartResponseDto {
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
