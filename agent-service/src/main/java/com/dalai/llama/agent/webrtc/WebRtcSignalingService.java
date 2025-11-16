package com.dalai.llama.agent.webrtc;

import com.dalai.llama.agent.entity.Agent;
import com.dalai.llama.agent.entity.CallSession;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebRTC Signaling Service
 * Handles WebRTC offer/answer exchange and ICE candidates
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebRtcSignalingService {

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    
    // Store active WebSocket sessions per agent
    private final Map<String, WebSocketSession> agentSessions = new ConcurrentHashMap<>();

    /**
     * Register agent's WebSocket session for signaling
     */
    public void registerAgentSession(String agentId, WebSocketSession session) {
        log.info("Registering WebRTC session for agent: {}", agentId);
        agentSessions.put(agentId, session);
        
        // Store in Redis
        String key = String.format("webrtc:session:%s", agentId);
        redis.opsForValue().set(key, session.getId());
    }

    /**
     * Unregister agent session
     */
    public void unregisterAgentSession(String agentId) {
        log.info("Unregistering WebRTC session for agent: {}", agentId);
        agentSessions.remove(agentId);
        
        String key = String.format("webrtc:session:%s", agentId);
        redis.delete(key);
    }

    /**
     * Send WebRTC offer to agent
     */
    public void sendOffer(String agentId, String callId, String sdpOffer) {
        log.info("Sending WebRTC offer to agent {} for call {}", agentId, callId);
        
        WebSocketSession session = agentSessions.get(agentId);
        if (session == null || !session.isOpen()) {
            log.error("No active WebSocket session for agent: {}", agentId);
            throw new RuntimeException("Agent not connected via WebRTC");
        }
        
        try {
            Map<String, Object> message = Map.of(
                "type", "offer",
                "callId", callId,
                "sdp", sdpOffer
            );
            
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(message)));
        } catch (IOException e) {
            log.error("Failed to send offer to agent", e);
            throw new RuntimeException("Failed to send WebRTC offer", e);
        }
    }

    /**
     * Handle WebRTC answer from agent
     */
    public void handleAnswer(String agentId, String callId, String sdpAnswer) {
        log.info("Received WebRTC answer from agent {} for call {}", agentId, callId);
        
        // Store answer in Redis for PBX to retrieve
        String key = String.format("webrtc:answer:%s", callId);
        redis.opsForValue().set(key, sdpAnswer);
        
        // Forward to media server/PBX
        forwardAnswerToPbx(callId, sdpAnswer);
    }

    /**
     * Handle ICE candidate from agent
     */
    public void handleIceCandidate(String agentId, String callId, Map<String, Object> candidate) {
        log.info("Received ICE candidate from agent {} for call {}", agentId, callId);
        
        try {
            String candidateJson = objectMapper.writeValueAsString(candidate);
            String key = String.format("webrtc:ice:%s", callId);
            redis.opsForList().leftPush(key, candidateJson);
            
            // Forward to media server
            forwardIceCandidateToPbx(callId, candidateJson);
        } catch (Exception e) {
            log.error("Failed to handle ICE candidate", e);
        }
    }

    /**
     * Send ICE candidate to agent
     */
    public void sendIceCandidate(String agentId, String callId, Map<String, Object> candidate) {
        WebSocketSession session = agentSessions.get(agentId);
        if (session == null || !session.isOpen()) {
            log.warn("Cannot send ICE candidate - agent session not available: {}", agentId);
            return;
        }
        
        try {
            Map<String, Object> message = Map.of(
                "type", "ice-candidate",
                "callId", callId,
                "candidate", candidate
            );
            
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(message)));
        } catch (IOException e) {
            log.error("Failed to send ICE candidate to agent", e);
        }
    }

    /**
     * Notify agent of incoming call
     */
    public void notifyIncomingCall(String agentId, CallSession callSession) {
        log.info("Notifying agent {} of incoming call {}", agentId, callSession.getCallId());
        
        WebSocketSession session = agentSessions.get(agentId);
        if (session == null || !session.isOpen()) {
            log.error("Cannot notify agent - no active session: {}", agentId);
            return;
        }
        
        try {
            Map<String, Object> message = Map.of(
                "type", "incoming-call",
                "callId", callSession.getCallId(),
                "from", callSession.getFrom(),
                "to", callSession.getTo(),
                "direction", callSession.getDirection().toString()
            );
            
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(message)));
        } catch (IOException e) {
            log.error("Failed to notify agent of incoming call", e);
        }
    }

    /**
     * Notify agent of call end
     */
    public void notifyCallEnded(String agentId, String callId) {
        log.info("Notifying agent {} that call {} ended", agentId, callId);
        
        WebSocketSession session = agentSessions.get(agentId);
        if (session == null || !session.isOpen()) {
            return;
        }
        
        try {
            Map<String, Object> message = Map.of(
                "type", "call-ended",
                "callId", callId
            );
            
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(message)));
        } catch (IOException e) {
            log.error("Failed to notify agent of call end", e);
        }
    }

    /**
     * Forward answer to PBX core
     */
    private void forwardAnswerToPbx(String callId, String sdpAnswer) {
        // TODO: Implement REST call to PBX core
        log.debug("Forwarding answer to PBX for call: {}", callId);
    }

    /**
     * Forward ICE candidate to PBX core
     */
    private void forwardIceCandidateToPbx(String callId, String candidate) {
        // TODO: Implement REST call to PBX core
        log.debug("Forwarding ICE candidate to PBX for call: {}", callId);
    }

    /**
     * Check if agent is connected via WebRTC
     */
    public boolean isAgentConnected(String agentId) {
        WebSocketSession session = agentSessions.get(agentId);
        return session != null && session.isOpen();
    }
}
