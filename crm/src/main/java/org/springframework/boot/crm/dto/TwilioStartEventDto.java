package org.springframework.boot.crm.dto;

import lombok.Data;
import org.springframework.web.socket.WebSocketSession;

@Data
public class TwilioStartEventDto  {
    private final WebSocketSession session;
    private final TwilioStartMessageDto twilioStartMediaMessage;

    public TwilioStartEventDto(Object source,  TwilioStartMessageDto twilioStartMediaMessage,
                               WebSocketSession session) {
        this.session = session;
        this.twilioStartMediaMessage = twilioStartMediaMessage;
    }
}