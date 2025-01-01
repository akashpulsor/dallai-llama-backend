package org.springframework.boot.crm.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.crm.dto.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Slf4j
@Data
public class MediaStreamHandler extends TextWebSocketHandler {
    private final ApplicationEventPublisher applicationEventPublisher;

    public MediaStreamHandler(ApplicationEventPublisher applicationEventPublisher) {
        this.applicationEventPublisher = applicationEventPublisher;
    }
    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        log.info("Twilio websocket connection established");
        log.info("Twilio and  open AI websocket connection established");

    }


    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        log.info(message.getPayload());
        TwilioMediaMessage twilioMediaMessage = objectMapper.readValue(message.getPayload(), TwilioMediaMessage.class);
        switch (twilioMediaMessage.getEvent()) {
            case "media":
                MediaEventDto mediaEventDto = objectMapper.readValue(message.getPayload(), MediaEventDto.class);
                log.info("Twilio and  open AI websocket connection established - {}",mediaEventDto);
                applicationEventPublisher.publishEvent(new TwilioMediaEventDto(this, 1,mediaEventDto));
                break;
            case "start":
                applicationEventPublisher.publishEvent(new TwilioStartEventDto(this, 1, twilioMediaMessage, session));
                log.info("Incoming Stream has started -{}",twilioMediaMessage.getStreamSid() );
                break;
            default:
                log.info("Received non-media event -{}",twilioMediaMessage.getEvent() );
                break;
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        applicationEventPublisher.publishEvent(new TwilioCloseEvent(this, session, status));
        log.info("Client disconnected");
    }
}
