package org.springframework.boot.crm.dto;

import com.twilio.rest.api.v2010.account.Recording;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.springframework.boot.crm.entity.CallLog;
import org.springframework.boot.crm.entity.CampaignRunData;
import org.springframework.boot.crm.entity.LlmData;
import org.springframework.boot.crm.entity.TwilioData;

@Data

public class TranscriptionDto {

    private Recording recording;
    private CallLog callLog;
    private String inboundData;
    private String outboundData;
    private CampaignRunData campaignRunData;
    private TwilioData twilioData;
    private LlmData llmData;

    public TranscriptionDto(Recording recording, CallLog callLog, String inboundData, String outboundData) {
        this.recording = recording;
        this.callLog = callLog;
        this.inboundData = inboundData;
        this.outboundData = outboundData;
    }
}
