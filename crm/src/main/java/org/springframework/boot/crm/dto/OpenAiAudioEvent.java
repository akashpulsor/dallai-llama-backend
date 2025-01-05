package org.springframework.boot.crm.dto;

import lombok.Data;
import org.springframework.context.ApplicationEvent;

@Data
public class OpenAiAudioEvent extends ApplicationEvent {

    private OpenAiAudioDto openAiAudioDto;
    private final TwilioStartEventDto twilioStartEventDto;
    public OpenAiAudioEvent(Object source, OpenAiAudioDto openAiAudioDto, TwilioStartEventDto twilioStartEventDto) {
        super(source);
        this.openAiAudioDto = openAiAudioDto;
        this.twilioStartEventDto = twilioStartEventDto;
    }
}
