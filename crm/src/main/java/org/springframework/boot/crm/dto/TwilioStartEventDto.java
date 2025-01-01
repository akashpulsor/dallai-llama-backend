package org.springframework.boot.crm.dto;

import org.springframework.web.socket.WebSocketSession;

public class TwilioStartEventDto extends TwilioEventDto {
    private final WebSocketSession session;
    public TwilioStartEventDto(Object source, int userId, TwilioMediaMessage twilioMediaMessage,
                               WebSocketSession session) {
        super(source, userId, twilioMediaMessage);
        this.session = session;
    }
}