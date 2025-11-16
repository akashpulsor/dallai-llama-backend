package com.dalai.llama.pbx.core.service;

import com.dalai.llama.pbx.core.cdr.CallRecordService;
import com.dalai.llama.pbx.core.kafka.RouteEventProducer;
import com.dalai.llama.pbx.core.rtpengine.RtpEngineClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Service to handle call lifecycle events and integrate with Agent Service
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CallEventService {

    private final StringRedisTemplate redis;
    private final CallRecordService callRecordService;
    private final RouteEventProducer eventProducer;
    private final ObjectMapper objectMapper;
    private final RestClient.Builder restClientBuilder;
    private final RtpEngineClient rtpEngineClient;

    @Value("${agent.service.url:http://localhost:8081}")
    private String agentServiceUrl;

    /**
     * Process call events from Kamailio/FreeSWITCH
     */
    public void processCallEvent(Map<String, Object> event) {
        String eventType = (String) event.get("event");
        String callId = (String) event.get("callId");

        log.info("Processing call event: {} for call {}", eventType, callId);

        switch (eventType) {
            case "INVITE":
                handleInvite(event);
                break;
            case "RINGING":
                handleRinging(event);
                break;
            case "ANSWER":
                handleAnswer(event);
                break;
            case "HANGUP":
                handleHangup(event);
                break;
            case "CANCEL":
                handleCancel(event);
                break;
            default:
                log.warn("Unknown call event type: {}", eventType);
        }

        // Store event in Redis for real-time tracking
        storeCallEvent(callId, event);
    }

    /**
     * Handle INVITE (new call)
     */
    private void handleInvite(Map<String, Object> event) {
        String callId = (String) event.get("callId");
        String from = (String) event.get("from");
        String to = (String) event.get("to");
        String tenantId = (String) event.get("tenantId");

        log.info("New INVITE: callId={}, from={}, to={}", callId, from, to);

        // Notify Agent Service about incoming call
        notifyAgentServiceIncomingCall(callId, tenantId, from, to);

        // Publish to Kafka
        publishEvent("call.invite", callId, event);
    }

    /**
     * Handle RINGING (call is ringing at destination)
     */
    private void handleRinging(Map<String, Object> event) {
        String callId = (String) event.get("callId");

        log.info("Call ringing: {}", callId);

        // Update call state in Redis
        redis.opsForValue().set("call:state:" + callId, "RINGING");

        publishEvent("call.ringing", callId, event);
    }

    /**
     * Handle ANSWER (call was answered)
     */
    private void handleAnswer(Map<String, Object> event) {
        String callId = (String) event.get("callId");
        String agentId = (String) event.get("agentId");

        log.info("Call answered: {} by agent: {}", callId, agentId);

        // Update call state
        redis.opsForValue().set("call:state:" + callId, "ACTIVE");

        // Start CDR recording
        //callRecordService.startCallRecord(callId, event);

        publishEvent("call.answered", callId, event);
    }

    /**
     * Handle HANGUP (call ended)
     */
    private void handleHangup(Map<String, Object> event) {
        String callId = (String) event.get("callId");

        log.info("Call hangup: {}", callId);

        // Update call state
        redis.opsForValue().set("call:state:" + callId, "ENDED");

        // Finalize CDR
        //callRecordService.endCallRecord(callId, event);

        // Cleanup Redis data
        cleanupCallData(callId);

        publishEvent("call.hangup", callId, event);
    }

    /**
     * Handle CANCEL (call was cancelled before answer)
     */
    private void handleCancel(Map<String, Object> event) {
        String callId = (String) event.get("callId");

        log.info("Call cancelled: {}", callId);

        redis.opsForValue().set("call:state:" + callId, "CANCELLED");

        cleanupCallData(callId);

        publishEvent("call.cancel", callId, event);
    }

    /**
     * Initiate call transfer
     */
    public void initiateTransfer(String callId, String destination) {
        log.info("Initiating transfer for call {} to {}", callId, destination);

        // In production, this would send commands to Kamailio/FreeSWITCH
        // For now, just store the transfer request
        Map<String, Object> transferEvent = new HashMap<>();
        transferEvent.put("callId", callId);
        transferEvent.put("destination", destination);
        transferEvent.put("type", "TRANSFER");

        publishEvent("call.transfer", callId, transferEvent);
    }

    /**
     * Process WebRTC SDP offer and generate answer
     */
    public String processWebRtcOffer(String callId, String sdpOffer) {
        log.info("Processing WebRTC offer for call: {}", callId);

        // Store offer in Redis
        redis.opsForValue().set("webrtc:offer:" + callId, sdpOffer);

        try {
            // Extract or generate SIP tags
            String fromTag = redis.opsForValue().get("call:from-tag:" + callId);
            String toTag = redis.opsForValue().get("call:to-tag:" + callId);

            if (fromTag == null) {
                fromTag = generateTag();
                redis.opsForValue().set("call:from-tag:" + callId, fromTag);
            }

            if (toTag == null) {
                toTag = generateTag();
                redis.opsForValue().set("call:to-tag:" + callId, toTag);
            }

            // Process through RTPEngine
            String sdpAnswer = rtpEngineClient.processOffer(callId, fromTag, toTag, sdpOffer);

            redis.opsForValue().set("webrtc:answer:" + callId, sdpAnswer);

            // Start recording if enabled
            String tenantId = redis.opsForValue().get("call:tenant:" + callId);
            if (tenantId != null && isRecordingEnabled(tenantId)) {
                String recordingPath = String.format("/recordings/%s/%s.wav", tenantId, callId);
                rtpEngineClient.startRecording(callId, recordingPath);
            }

            return sdpAnswer;

        } catch (Exception e) {
            log.error("Error processing WebRTC offer through RTPEngine", e);
            // Fallback to placeholder for backwards compatibility
            return generatePlaceholderSdpAnswer(sdpOffer);
        }
    }

    /**
     * Process ICE candidate
     */
    public void processIceCandidate(String callId, Map<String, Object> candidate) {
        log.debug("Processing ICE candidate for call: {}", callId);

        try {
            String candidateJson = objectMapper.writeValueAsString(candidate);
            redis.opsForList().leftPush("webrtc:ice:" + callId, candidateJson);
        } catch (Exception e) {
            log.error("Error storing ICE candidate", e);
        }
    }

    /**
     * Notify Agent Service about incoming call
     */
    private void notifyAgentServiceIncomingCall(String callId, String tenantId, String from, String to) {
        RestClient client = restClientBuilder.build();

        // First, determine which agent should receive the call (from routing decision)
        String routeKey = "call:route:" + callId;
        String agentId = redis.opsForValue().get(routeKey);

        if (agentId == null) {
            log.warn("No agent assignment found for call: {}", callId);
            return;
        }

        Map<String, Object> request = new HashMap<>();
        request.put("callId", callId);
        request.put("agentId", Long.parseLong(agentId));
        request.put("tenantId", tenantId);
        request.put("from", from);
        request.put("to", to);

        try {
            client.post()
                    .uri(agentServiceUrl + "/api/v1/calls/incoming")
                    .body(request)
                    .retrieve()
                    .toEntity(String.class);
        } catch (Exception e) {
            log.error("Failed to notify agent service", e);
        }
    }

    /**
     * Store call event in Redis for tracking
     */
    private void storeCallEvent(String callId, Map<String, Object> event) {
        try {
            String eventJson = objectMapper.writeValueAsString(event);
            redis.opsForList().leftPush("call:events:" + callId, eventJson);
        } catch (Exception e) {
            log.error("Error storing call event", e);
        }
    }

    /**
     * Publish event to Kafka
     */
    private void publishEvent(String eventType, String callId, Map<String, Object> event) {
        try {
            event.put("eventType", eventType);
            String payload = objectMapper.writeValueAsString(event);
            eventProducer.publish(eventType, callId, payload);
        } catch (Exception e) {
            log.error("Error publishing event", e);
        }
    }

    /**
     * Cleanup call data from Redis
     */
    private void cleanupCallData(String callId) {
        // Delete call from RTPEngine
        try {
            rtpEngineClient.deleteCall(callId);
        } catch (Exception e) {
            log.error("Error deleting call from RTPEngine", e);
        }

        redis.delete("call:state:" + callId);
        redis.delete("call:route:" + callId);
        redis.delete("call:from-tag:" + callId);
        redis.delete("call:to-tag:" + callId);
        redis.delete("call:tenant:" + callId);
        redis.delete("webrtc:offer:" + callId);
        redis.delete("webrtc:answer:" + callId);
        redis.delete("webrtc:ice:" + callId);

        // Keep events for a while for debugging
        redis.expire("call:events:" + callId, 1, TimeUnit.HOURS); // 1 hour
    }

    /**
     * Generate SIP tag
     */
    private String generateTag() {
        return "tag-" + System.currentTimeMillis() + "-" +
                (int)(Math.random() * 10000);
    }

    /**
     * Check if recording is enabled for tenant
     */
    private boolean isRecordingEnabled(String tenantId) {
        String enabled = redis.opsForValue().get("tenant:recording:" + tenantId);
        return "true".equals(enabled);
    }

    /**
     * Generate placeholder SDP answer (fallback)
     */
    private String generatePlaceholderSdpAnswer(String sdpOffer) {
        return "v=0\r\n" +
                "o=- 0 0 IN IP4 127.0.0.1\r\n" +
                "s=PBX Core\r\n" +
                "c=IN IP4 127.0.0.1\r\n" +
                "t=0 0\r\n" +
                "m=audio 50000 RTP/AVP 0 8\r\n" +
                "a=rtpmap:0 PCMU/8000\r\n" +
                "a=rtpmap:8 PCMA/8000\r\n";
    }
}
