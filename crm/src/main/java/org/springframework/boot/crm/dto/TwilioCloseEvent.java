package org.springframework.boot.crm.dto;

import lombok.Data;
import org.springframework.context.ApplicationEvent;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

@Data
public class TwilioCloseEvent extends ApplicationEvent {

    WebSocketSession session;
    CloseStatus status;
    public TwilioCloseEvent(Object source, WebSocketSession session, CloseStatus status) {
        super(source);
        this.session = session;
        this.status = status;
    }
}