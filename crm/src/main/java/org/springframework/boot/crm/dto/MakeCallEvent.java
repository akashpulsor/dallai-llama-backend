package org.springframework.boot.crm.dto;

import lombok.Data;
import org.springframework.boot.crm.entity.*;
import org.springframework.context.ApplicationEvent;

import java.util.HashSet;
import java.util.List;

@Data
public class MakeCallEvent extends ApplicationEvent {

    private int businessId;
    private CampaignRunData campaignRunData;
    private TwilioData twilioData ;
    private CampaignData campaignData;
    private AgentData agentData;
    private LlmData llmData;

    public MakeCallEvent(Object source, int businessId, CampaignRunData campaignRunData,
                         TwilioData twilioData, CampaignData campaignData, AgentData agentData, LlmData llmData) {
        super(source);
        this.businessId = businessId;
        this.campaignRunData = campaignRunData;
        this.twilioData = twilioData;
        this.campaignData = campaignData;
        this.agentData = agentData;
        this.llmData = llmData;
    }
}
