package org.springframework.boot.crm.dto;

import lombok.Data;
import org.springframework.context.ApplicationEvent;

@Data
public class StartCallEvent extends ApplicationEvent {

    private final TwilioStartEventDto twilioStartEventDto;
    public StartCallEvent(Object source, TwilioStartEventDto twilioStartEventDto) {
        super(source);
        this.twilioStartEventDto = twilioStartEventDto;
    }
}
