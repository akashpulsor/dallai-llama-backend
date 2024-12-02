package org.springframework.boot.crm.entity;

import jakarta.persistence.*;
import lombok.Data;


@Data
@Entity(name = "campaign_data")
public class CampaignData {

    @Id
    @GeneratedValue(strategy= GenerationType.IDENTITY)
    @Column(name="campaign_id")
    private int campaignId;

    @Column(name="business_id")
    private  int businessId;

    @Column(name="campaign_aim")
    private String campaignAim;

    @Column(name="campaign_desc")
    private String campaignDesc;

    @Column(name="campaign_img_url")
    private String campaignImgUrl;

    @Column(name="campaign_name")
    private String campaignName;

    @Column(name="campaign_prompt")
    private String campaignPrompt;

    @Column(name="conversation_guide_lines")
    private String conversationGuideLines;

    @Column(name="first_message")
    private String firstMessage;

    @Column(name="handling_faq")
    private String handlingFaq;

    @Column(name="placing_order")
    private String placingOrder;

    @Column(name="is_active")
    private boolean isActive;

    @Column(name="language")
    private String language;

    @Column(name="duration")
    private int duration;

    @Embedded
    private SanitaryColumn sanitaryColumn;

}
