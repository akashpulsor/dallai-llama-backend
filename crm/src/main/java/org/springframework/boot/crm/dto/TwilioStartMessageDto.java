package org.springframework.boot.crm.dto;

import lombok.Data;

@Data
public class TwilioStartMessageDto {
    private String event;
    private String sequenceNumber;
    private StartDto start;
    private String streamSid;

    @Data
    public static class StartDto {
        private String accountSid;
        private String streamSid;
        private String callSid;
        private Object tracks;
        private Object mediaFormat;
        private CustomParameterDto customParameters;
    }

    @Data
    public static class CustomParameterDto {
        private int campaignRunId;
        private int businessId;
        private int leadId;
        private String authToken;
    }
}
