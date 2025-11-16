package com.dalai.llama.agent.webrtc;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;

/**
 * WebSocket handler for WebRTC signaling messages
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebRtcSignalingHandler extends TextWebSocketHandler {

    private final WebRtcSignalingService signalingService;
    private final ObjectMapper objectMapper;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        log.info("WebSocket connection established: {}", session.getId());
        
        // Extract agent ID from session attributes or URI
        String agentId = extractAgentId(session);
        if (agentId != null) {
            signalingService.registerAgentSession(agentId, session);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String payload = message.getPayload();
        log.debug("Received WebSocket message: {}", payload);
        
        try {
            Map<String, Object> data = objectMapper.readValue(payload, Map.class);
            String type = (String) data.get("type");
            String agentId = extractAgentId(session);
            
            if (agentId == null) {
                log.error("No agent ID found in session");
                return;
            }
            
            switch (type) {
                case "answer":
                    handleAnswer(agentId, data);
                    break;
                case "ice-candidate":
                    handleIceCandidate(agentId, data);
                    break;
                case "call-answer":
                    handleCallAnswer(agentId, data);
                    break;
                case "call-reject":
                    handleCallReject(agentId, data);
                    break;
                case "call-hangup":
                    handleCallHangup(agentId, data);
                    break;
                default:
                    log.warn("Unknown message type: {}", type);
            }
        } catch (Exception e) {
            log.error("Error processing WebSocket message", e);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        log.info("WebSocket connection closed: {} - {}", session.getId(), status);
        
        String agentId = extractAgentId(session);
        if (agentId != null) {
            signalingService.unregisterAgentSession(agentId);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.error("WebSocket transport error: {}", session.getId(), exception);
        session.close();
    }

    private void handleAnswer(String agentId, Map<String, Object> data) {
        String callId = (String) data.get("callId");
        String sdp = (String) data.get("sdp");
        
        signalingService.handleAnswer(agentId, callId, sdp);
    }

    private void handleIceCandidate(String agentId, Map<String, Object> data) {
        String callId = (String) data.get("callId");
        Map<String, Object> candidate = (Map<String, Object>) data.get("candidate");
        
        signalingService.handleIceCandidate(agentId, callId, candidate);
    }

    private void handleCallAnswer(String agentId, Map<String, Object> data) {
        String callId = (String) data.get("callId");
        log.info("Agent {} answered call {}", agentId, callId);
        
        // This will be handled by CallSessionService
    }

    private void handleCallReject(String agentId, Map<String, Object> data) {
        String callId = (String) data.get("callId");
        log.info("Agent {} rejected call {}", agentId, callId);
    }

    private void handleCallHangup(String agentId, Map<String, Object> data) {
        String callId = (String) data.get("callId");
        log.info("Agent {} hung up call {}", agentId, callId);
    }

    private String extractAgentId(WebSocketSession session) {
        // Extract from session attributes (set during handshake)
        Object agentId = session.getAttributes().get("agentId");
        if (agentId != null) {
            return agentId.toString();
        }
        
        // Or extract from URI path
        String path = session.getUri().getPath();
        // Expecting path like /ws/agent/{agentId}
        String[] parts = path.split("/");
        if (parts.length > 0) {
            return parts[parts.length - 1];
        }
        
        return null;
    }
}
