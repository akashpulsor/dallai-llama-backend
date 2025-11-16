package com.dalai.llama.pbx.core.controller;

import com.dalai.llama.pbx.core.service.CallEventService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Controller to handle call lifecycle events from Kamailio/FreeSWITCH
 */
@Slf4j
@RestController
@RequestMapping("/v1/calls")
@RequiredArgsConstructor
public class CallEventsController {

    private final CallEventService callEventService;

    /**
     * Handle call events from signaling layer (Kamailio)
     * Events: INVITE, RINGING, ANSWER, HANGUP, etc.
     */
    @PostMapping("/events")
    public ResponseEntity<Map<String, Object>> handleCallEvent(@RequestBody Map<String, Object> event) {
        String eventType = (String) event.get("event");
        String callId = (String) event.get("callId");
        
        log.info("Received call event: {} for call: {}", eventType, callId);
        
        try {
            callEventService.processCallEvent(event);
            return ResponseEntity.ok(Map.of("status", "accepted"));
        } catch (Exception e) {
            log.error("Error processing call event", e);
            return ResponseEntity.internalServerError()
                .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Handle transfer requests
     */
    @PostMapping("/transfer")
    public ResponseEntity<Map<String, Object>> transferCall(@RequestBody Map<String, Object> request) {
        String callId = (String) request.get("callId");
        String destination = (String) request.get("destination");
        
        log.info("Transfer request for call {} to {}", callId, destination);
        
        try {
            callEventService.initiateTransfer(callId, destination);
            return ResponseEntity.ok(Map.of("status", "transferred"));
        } catch (Exception e) {
            log.error("Error transferring call", e);
            return ResponseEntity.internalServerError()
                .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * WebRTC signaling endpoint for SDP offer/answer exchange
     */
    @PostMapping("/webrtc/offer")
    public ResponseEntity<Map<String, Object>> handleWebRtcOffer(@RequestBody Map<String, Object> request) {
        String callId = (String) request.get("callId");
        String sdpOffer = (String) request.get("sdp");
        
        log.info("WebRTC offer received for call: {}", callId);
        
        try {
            String sdpAnswer = callEventService.processWebRtcOffer(callId, sdpOffer);
            return ResponseEntity.ok(Map.of(
                "callId", callId,
                "sdp", sdpAnswer
            ));
        } catch (Exception e) {
            log.error("Error processing WebRTC offer", e);
            return ResponseEntity.internalServerError()
                .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Handle ICE candidates for WebRTC
     */
    @PostMapping("/webrtc/ice")
    public ResponseEntity<Map<String, Object>> handleIceCandidate(@RequestBody Map<String, Object> request) {
        String callId = (String) request.get("callId");
        
        log.debug("ICE candidate received for call: {}", callId);
        
        try {
            callEventService.processIceCandidate(callId, request);
            return ResponseEntity.ok(Map.of("status", "accepted"));
        } catch (Exception e) {
            log.error("Error processing ICE candidate", e);
            return ResponseEntity.internalServerError()
                .body(Map.of("error", e.getMessage()));
        }
    }
}
