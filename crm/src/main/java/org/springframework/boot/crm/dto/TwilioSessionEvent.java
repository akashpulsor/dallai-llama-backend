package org.springframework.boot.crm.dto;

import lombok.Data;
import org.springframework.context.ApplicationEvent;
import org.springframework.web.socket.WebSocketSession;

@Data
public class TwilioSessionEvent extends ApplicationEvent {

    private final WebSocketSession session;


    public TwilioSessionEvent(Object source, WebSocketSession session) {
        super(source);
        this.session = session;
    }

}