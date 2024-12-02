package org.springframework.boot.crm.dto;

import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.Data;
import org.springframework.boot.crm.entity.CampaignData;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;

import java.time.LocalDateTime;

@Data
public class CampaignDataResponseDto {
    private int campaignId;
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
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
