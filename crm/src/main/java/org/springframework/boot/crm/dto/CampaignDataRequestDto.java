package org.springframework.boot.crm.dto;


import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import org.springframework.validation.annotation.Validated;

import java.time.LocalDateTime;

@Data
@Validated
public class CampaignDataRequestDto {

    private int campaignId;

    @NotNull(message = "Business Id is mandatory")
    @Min(value = 1, message = "Business Id must be greater than zero")
    private  int businessId;

    @NotBlank(message = "Objective of campaign is mandatory")
    private String campaignAim;

    @NotBlank(message = "Description of campaign is mandatory")
    private String campaignDesc;

    //TODO not implemented in front end
    private String campaignImgUrl;

    @NotBlank(message = "Name of campaign is mandatory")
    private String campaignName;


    private String language;

    @NotBlank(message = "Conversation Guideline  is mandatory")
    private String conversationGuideLines;

    @NotBlank(message = "First Message  is mandatory")
    private String firstMessage;

    //TODO not implemented in front end
    private String handlingFaq;

    //TODO not implemented in front end
    private String placingOrder;

    private boolean isActive;

    @Positive(message = "duration must be positive and between 2 and 5")
    private int duration = 2;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
