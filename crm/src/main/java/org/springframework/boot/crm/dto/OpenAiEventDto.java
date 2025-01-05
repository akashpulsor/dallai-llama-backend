package org.springframework.boot.crm.dto;

import org.springframework.context.ApplicationEvent;

public class OpenAiEventDto extends ApplicationEvent {
    private final OpenAiResponseDto openAiResponseDto;

    public OpenAiEventDto(Object source, OpenAiResponseDto openAiResponseDto) {
        super(source);
        this.openAiResponseDto = openAiResponseDto;

    }


    public OpenAiResponseDto getOpenAiResponseDto(){
        return this.openAiResponseDto;
    }
}
