package org.springframework.boot.crm.dto;

import lombok.Data;
import org.springframework.context.ApplicationEvent;

@Data
public class TwilioMediaEventDto extends ApplicationEvent {

    private MediaEventDto mediaEventDto;
    private int userId;

    public TwilioMediaEventDto(Object source, int userId, MediaEventDto mediaEventDto) {
        super(source);
        this.userId = userId;
        this.mediaEventDto = mediaEventDto;
    }
}