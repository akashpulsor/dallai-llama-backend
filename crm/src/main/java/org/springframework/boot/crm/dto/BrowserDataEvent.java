package org.springframework.boot.crm.dto;

import lombok.Data;
import org.springframework.context.ApplicationEvent;
import org.springframework.web.socket.WebSocketSession;

@Data
public class BrowserDataEvent extends ApplicationEvent {

    private WebSocketSession webSocketSession;
    private BrowserDataDto browserDataDto;

    public BrowserDataEvent(Object source, WebSocketSession session, BrowserDataDto browserDataDto ) {
        super(source);
        this.webSocketSession = session;
        this.browserDataDto = browserDataDto;
    }
}
