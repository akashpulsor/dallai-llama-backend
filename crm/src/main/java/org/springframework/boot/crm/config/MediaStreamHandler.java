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

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
        // Extract parameters from handshake headers
        Map<String, List<String>> headers = session.getHandshakeHeaders();

        // Debug logging for session details
        URI uri = session.getUri();
        log.info("WebSocket URI: {}", uri);
        log.info("URI Query: {}", uri != null ? uri.getQuery() : "null");

        Map<String, Object> attributes = session.getAttributes();
        log.info("Session attributes size: {}", attributes.size());
        log.info("Session attributes: {}", attributes);

        // Log all headers
        session.getHandshakeHeaders().forEach((key, value) -> {
            log.info("Header - {}: {}", key, value);
        });

        //log.info("Connection established with parameters - campaignRunId: {}, businessId: {}, leadId: {}, callType: {}",
         //       campaignRunId, businessId, leadId, callType);
        super.afterConnectionEstablished(session);

    }

    private String getHeaderValue(Map<String, List<String>> headers, String headerName) {
        List<String> values = headers.get(headerName);
        return values != null && !values.isEmpty() ? values.get(0) : null;
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
                //log.info("Twilio and  open AI websocket connection established - {}",mediaEventDto);
                applicationEventPublisher.publishEvent(new TwilioMediaEventDto(this, 1,mediaEventDto));
                break;
            case "start":
                TwilioStartMessageDto twilioStartMediaMessage = objectMapper.readValue(message.getPayload(), TwilioStartMessageDto.class);
                WebSocketSession webSocketSession = addDataInWebSocketSession(session, twilioStartMediaMessage);
                super.handleTextMessage(webSocketSession,message);
                applicationEventPublisher.publishEvent(new TwilioStartEventDto(this,  twilioStartMediaMessage, session));
                //log.info("Incoming Stream has started -{}",twilioMediaMessage.getStreamSid() );
                break;
            default:
                //log.info("Received non-media event -{}",twilioMediaMessage.getEvent() );
                break;
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        Map<String, Object> sessionAttributes = session.getAttributes();
        log.info("Closing connection for businessId: {}, callType: {}",
                sessionAttributes.get("businessId"),
                sessionAttributes.get("callType"));
        super.afterConnectionEstablished(session);
        applicationEventPublisher.publishEvent(new TwilioCloseEvent(this, session, status));
        log.info("Client disconnected");
    }


    
    public WebSocketSession addDataInWebSocketSession(WebSocketSession webSocketSession, TwilioStartMessageDto twilioStartMediaMessage){
        Map<String, Object> attributes = webSocketSession.getAttributes();
        TwilioStartMessageDto.CustomParameterDto customerParameterDto = twilioStartMediaMessage.getStart().getCustomParameters();
        attributes.put("callType",customerParameterDto.getCallType());
        attributes.put("leadId",customerParameterDto.getLeadId());
        attributes.put("businessId",customerParameterDto.getBusinessId());
        attributes.put("campaignRunId",customerParameterDto.getCampaignRunId());
        return webSocketSession;
    }

}
