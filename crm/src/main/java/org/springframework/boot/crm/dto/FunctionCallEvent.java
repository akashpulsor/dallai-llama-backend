package org.springframework.boot.crm.dto;

import lombok.Data;
import org.springframework.context.ApplicationEvent;

@Data
public class FunctionCallEvent extends ApplicationEvent {

    private final  FunctionCallDto functionCallDto;
    private final TwilioStartEventDto twilioStartEventDto;
    public FunctionCallEvent(Object source, FunctionCallDto functionCallDto,
                             TwilioStartEventDto twilioStartEventDto) {
        super(source);
        this.functionCallDto = functionCallDto;
        this.twilioStartEventDto = twilioStartEventDto;
    }
}
