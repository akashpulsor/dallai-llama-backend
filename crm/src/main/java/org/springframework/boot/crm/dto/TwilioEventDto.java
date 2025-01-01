package org.springframework.boot.crm.dto;

import org.springframework.context.ApplicationEvent;

public class TwilioEventDto  extends ApplicationEvent {

    private final int userId;
    private final TwilioMediaMessage twilioMediaMessage;


    public TwilioEventDto(Object source, int userId, TwilioMediaMessage twilioMediaMessage) {
        super(source);
        this.twilioMediaMessage = twilioMediaMessage;
        this.userId = userId;
    }

    public TwilioMediaMessage getTwilioMediaMessage() {
        return this.twilioMediaMessage;
    }

    public int getUserId() {
        return this.userId;
    }
}

