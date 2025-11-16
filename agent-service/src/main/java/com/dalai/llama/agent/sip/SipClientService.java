package com.dalai.llama.agent.sip;

import com.dalai.llama.agent.entity.Agent;
import com.dalai.llama.agent.entity.CallSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.Map;

/**
 * SIP Client Service - Handles SIP registration and call signaling
 * Integration with Kamailio/FreeSWITCH via REST API
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SipClientService {

    private final StringRedisTemplate redis;
    private final RestClient.Builder restClientBuilder;

    @Value("${pbx.core.url:http://localhost:8080}")
    private String pbxCoreUrl;

    @Value("${sip.proxy.host:localhost}")
    private String sipProxyHost;

    @Value("${sip.proxy.port:5060}")
    private int sipProxyPort;

    /**
     * Register agent's SIP endpoint with the PBX
     */
    public void registerAgentSipEndpoint(Agent agent, String sipUri) {
        log.info("Registering SIP endpoint for agent {}: {}", agent.getId(), sipUri);
        
        // Store in Redis for fast lookup
        String key = String.format("contacts:agent:%s", agent.getId());
        redis.opsForList().leftPush(key, sipUri);
        
        // Also store WebRTC signaling endpoint if available
        String webrtcUri = String.format("webrtc:%s", agent.getId());
        redis.opsForList().leftPush(key, webrtcUri);
        
        // Mark agent as available for calls
        String availabilityKey = String.format("agent:availability:%s:%s", 
            agent.getTenantId(), agent.getId());
        redis.opsForValue().set(availabilityKey, "AVAILABLE");
    }

    /**
     * Initiate outbound call from agent
     */
    public CallSession initiateOutboundCall(Agent agent, String destination) {
        log.info("Initiating outbound call from agent {} to {}", agent.getId(), destination);
        
        RestClient client = restClientBuilder.build();
        
        Map<String, Object> request = new HashMap<>();
        request.put("tenantId", agent.getTenantId());
        request.put("from", agent.getSipUsername());
        request.put("to", destination);
        request.put("agentId", agent.getId().toString());
        
        try {
            var response = client.post()
                .uri(pbxCoreUrl + "/v1/calls/egress")
                .body(request)
                .retrieve()
                .toEntity(Map.class);
            
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                String callId = (String) response.getBody().get("callId");
                log.info("Outbound call initiated with callId: {}", callId);
                
                // Create call session
                CallSession session = new CallSession();
                session.setCallId(callId);
                session.setAgentId(agent.getId());
                session.setTenantId(agent.getTenantId());
                session.setDirection(CallSession.Direction.OUTBOUND);
                session.setFrom(agent.getSipUsername());
                session.setTo(destination);
                
                return session;
            }
        } catch (Exception e) {
            log.error("Failed to initiate outbound call", e);
            throw new RuntimeException("Failed to initiate call: " + e.getMessage());
        }
        
        throw new RuntimeException("Failed to initiate outbound call");
    }

    /**
     * Answer incoming call
     */
    public void answerCall(CallSession callSession) {
        log.info("Answering call {} for agent {}", 
            callSession.getCallId(), callSession.getAgentId());
        
        // Send SIP 200 OK via signaling layer
        // In production, this would trigger actual SIP INVITE answer
        notifyPbxCallAnswered(callSession);
    }

    /**
     * End/Hangup call
     */
    public void endCall(CallSession callSession) {
        log.info("Ending call {} for agent {}", 
            callSession.getCallId(), callSession.getAgentId());
        
        // Send SIP BYE
        notifyPbxCallEnded(callSession);
    }

    /**
     * Hold/Resume call
     */
    public void holdCall(CallSession callSession, boolean hold) {
        log.info("{} call {} for agent {}", 
            hold ? "Holding" : "Resuming", 
            callSession.getCallId(), 
            callSession.getAgentId());
        
        // Send re-INVITE with a=sendonly/sendrecv
        String key = String.format("call:hold:%s", callSession.getCallId());
        redis.opsForValue().set(key, hold ? "HELD" : "ACTIVE");
    }

    /**
     * Transfer call to another agent or number
     */
    public void transferCall(CallSession callSession, String destination) {
        log.info("Transferring call {} to {}", callSession.getCallId(), destination);
        
        RestClient client = restClientBuilder.build();
        
        Map<String, Object> request = new HashMap<>();
        request.put("callId", callSession.getCallId());
        request.put("destination", destination);
        request.put("transferType", "blind");
        
        try {
            client.post()
                .uri(pbxCoreUrl + "/v1/calls/transfer")
                .body(request)
                .retrieve()
                .toEntity(String.class);
        } catch (Exception e) {
            log.error("Failed to transfer call", e);
            throw new RuntimeException("Failed to transfer call: " + e.getMessage());
        }
    }

    /**
     * Notify PBX that call was answered
     */
    private void notifyPbxCallAnswered(CallSession callSession) {
        RestClient client = restClientBuilder.build();
        
        Map<String, Object> event = new HashMap<>();
        event.put("callId", callSession.getCallId());
        event.put("agentId", callSession.getAgentId().toString());
        event.put("status", "ANSWERED");
        
        try {
            client.post()
                .uri(pbxCoreUrl + "/v1/calls/events")
                .body(event)
                .retrieve()
                .toEntity(String.class);
        } catch (Exception e) {
            log.error("Failed to notify PBX of call answer", e);
        }
    }

    /**
     * Notify PBX that call was ended
     */
    private void notifyPbxCallEnded(CallSession callSession) {
        RestClient client = restClientBuilder.build();
        
        Map<String, Object> event = new HashMap<>();
        event.put("callId", callSession.getCallId());
        event.put("agentId", callSession.getAgentId().toString());
        event.put("status", "ENDED");
        
        try {
            client.post()
                .uri(pbxCoreUrl + "/v1/calls/events")
                .body(event)
                .retrieve()
                .toEntity(String.class);
        } catch (Exception e) {
            log.error("Failed to notify PBX of call end", e);
        }
    }

    /**
     * Unregister agent when they go offline
     */
    public void unregisterAgent(Agent agent) {
        log.info("Unregistering agent {}", agent.getId());
        
        String key = String.format("contacts:agent:%s", agent.getId());
        redis.delete(key);
        
        String availabilityKey = String.format("agent:availability:%s:%s", 
            agent.getTenantId(), agent.getId());
        redis.opsForValue().set(availabilityKey, "OFFLINE");
    }
}
