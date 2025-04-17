package org.springframework.boot.crm.dto;

import lombok.Data;
import org.springframework.boot.crm.entity.CallLog;
import org.springframework.context.ApplicationEvent;

@Data
public class TranscriptionEvent extends ApplicationEvent {

    private int campaignRunId;
    private int businessId ;
    private int leadId ;
    private int callId;
    private String transcriptionText;

    public TranscriptionEvent(Object source, int campaignRunId, int businessId, int leadId, int callId, String transcriptionText) {
        super(source);
        this.campaignRunId = campaignRunId;
        this.businessId = businessId;
        this.leadId = leadId;
        this.callId = callId;
        this.transcriptionText = transcriptionText;
    }
}
