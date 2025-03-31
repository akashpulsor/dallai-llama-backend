package org.springframework.boot.crm.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.crm.dto.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;



@Slf4j
public class BrowserSessionHandler extends TextWebSocketHandler {
    private final ApplicationEventPublisher applicationEventPublisher;

    public BrowserSessionHandler(ApplicationEventPublisher applicationEventPublisher) {
        this.applicationEventPublisher = applicationEventPublisher;
    }
    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {


    }



    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        log.info("Message recieved - {}",message);
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        log.info(message.getPayload());
        BrowserDataDto browserDataDto = objectMapper.readValue(message.getPayload(), BrowserDataDto.class);
        //log.info("Deserialized Data - {}",browserDataDto);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {

        log.info("Client disconnected - {}",status);
    }




}
