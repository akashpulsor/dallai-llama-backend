package org.springframework.boot.crm.dto;

import lombok.Data;
import org.springframework.boot.crm.entity.CampaignData;
import org.springframework.boot.crm.entity.CampaignRunData;
import org.springframework.boot.crm.entity.LeadData;
import org.springframework.boot.crm.entity.TwilioData;
import org.springframework.context.ApplicationEvent;

import java.util.HashSet;
import java.util.List;

@Data
public class MakeCallEvent extends ApplicationEvent {

    private int businessId;
    private CampaignRunData campaignRunData;
    private TwilioData twilioData ;
    private CampaignData campaignData;

    public MakeCallEvent(Object source, int businessId, CampaignRunData campaignRunData,
                         TwilioData twilioData, CampaignData campaignData) {
        super(source);
        this.businessId = businessId;
        this.campaignRunData = campaignRunData;
        this.twilioData = twilioData;
        this.campaignData = campaignData;
    }
}
