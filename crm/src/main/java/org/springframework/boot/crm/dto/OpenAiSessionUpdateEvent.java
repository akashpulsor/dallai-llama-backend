package org.springframework.boot.crm.dto;

public class OpenAiSessionUpdateEvent extends OpenAiEventDto {
    public OpenAiSessionUpdateEvent(Object source, OpenAiResponseDto openAiResponseDto) {
        super(source, openAiResponseDto);
    }
}

