package org.springframework.boot.crm.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.validation.annotation.Validated;

import java.util.List;
import java.util.Set;

@Data
@Validated
public class CampaignStartRequestDto {

    @NotNull(message = "Campaign Id is mandatory")
    private int campaignId;

    @NotNull(message = "Business Id is mandatory")
    private int businessId;

    private boolean all;

    private Set<Integer> leadList;

    @NotNull(message = "Agent Id is mandatory")
    private int agentId;

    @NotNull(message = "Agent Id is mandatory")
    private String language;

    @NotNull(message = "Llm Id is mandatory")
    private int llmId;

    @NotNull(message = "Phone Id is mandatory")
    private int phoneId;
}
