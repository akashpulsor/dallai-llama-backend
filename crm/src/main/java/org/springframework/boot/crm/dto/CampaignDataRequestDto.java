package org.springframework.boot.crm.dto;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import lombok.Data;
import org.springframework.boot.crm.entity.SanitaryColumn;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;

import java.time.LocalDateTime;

@Data
public class CampaignDataRequestDto {

    private  int businessId;

    private String campaignAim;

    private String campaignDesc;

    private String campaignImgUrl;

    private String campaignName;

    private String campaignPrompt;

    private String conversationGuideLines;

    private String firstMessage;

    private String handlingFaq;

    private String placingOrder;

    private boolean isActive;

    private int duration;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
