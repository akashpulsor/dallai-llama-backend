package org.springframework.boot.crm.dto;

import lombok.Data;
import org.springframework.context.ApplicationEvent;

@Data
public class OpenAiResponseDoneEvent extends ApplicationEvent {

    private final OpenAiResponseDoneDto openAiResponseDoneDto;
    private final TwilioStartEventDto twilioStartEventDto;

    public OpenAiResponseDoneEvent(Object source, OpenAiResponseDoneDto openAiResponseDoneDto,
                                   TwilioStartEventDto twilioStartEventDto) {
        super(source);
        this.openAiResponseDoneDto = openAiResponseDoneDto;
        this.twilioStartEventDto = twilioStartEventDto;
    }
}
