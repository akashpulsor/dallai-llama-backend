package org.springframework.boot.crm.dto;

import lombok.Data;
import org.springframework.context.ApplicationEvent;

@Data
public class StartCallEvent extends ApplicationEvent {

    private final TwilioStartEventDto twilioStartEventDto;
    private final int businessId;
    private final int leadId;
    private final int campaignId;
    private final int campaignRunId;
    private final int agentId;
    private final int llmId;
    private final int phoneId;
    public StartCallEvent(Object source, TwilioStartEventDto twilioStartEventDto,
                          int businessId,
                          int leadId,
                          int campaignId,
                          int campaignRunId,
                          int agentId,
                          int llmId,int phoneId) {
        super(source);
        this.twilioStartEventDto = twilioStartEventDto;
        this.businessId = businessId;
        this.leadId = leadId;
        this.campaignId = campaignId;
        this.campaignRunId = campaignRunId;
        this.agentId = agentId;
        this.llmId = llmId;
        this.phoneId = phoneId;
    }
}
