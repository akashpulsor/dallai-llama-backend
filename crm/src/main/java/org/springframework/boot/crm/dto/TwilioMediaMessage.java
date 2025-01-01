package org.springframework.boot.crm.dto;

import lombok.Data;

@Data
public class TwilioMediaMessage {
    private String event;
    private String streamSid;
}
