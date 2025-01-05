package org.springframework.boot.crm.dto;

public class OpenAiSessionCreateEvent extends OpenAiEventDto{

    public OpenAiSessionCreateEvent(Object source, OpenAiResponseDto openAiResponseDto) {
        super(source, openAiResponseDto);
    }
}
