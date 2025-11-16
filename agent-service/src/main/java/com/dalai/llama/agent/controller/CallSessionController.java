package com.dalai.llama.agent.controller;

import com.dalai.llama.agent.entity.CallSession;
import com.dalai.llama.agent.service.CallSessionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/calls")
@RequiredArgsConstructor
public class CallSessionController {

    private final CallSessionService callSessionService;

    @GetMapping("/{callId}")
    @PreAuthorize("hasAuthority('SCOPE_call:read')")
    public ResponseEntity<CallSession> getCall(@PathVariable String callId) {
        return callSessionService.findByCallId(callId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    @PreAuthorize("hasAuthority('SCOPE_call:write')")
    public ResponseEntity<CallSession> createCall(@RequestBody CallSession callSession) {
        return ResponseEntity.ok(callSessionService.createCallSession(callSession));
    }

    @PostMapping("/{callId}/answer")
    @PreAuthorize("hasAuthority('SCOPE_call:write')")
    public ResponseEntity<CallSession> answerCall(@PathVariable String callId) {
        return ResponseEntity.ok(callSessionService.answerCall(callId));
    }

    @PostMapping("/{callId}/end")
    @PreAuthorize("hasAuthority('SCOPE_call:write')")
    public ResponseEntity<CallSession> endCall(@PathVariable String callId) {
        return ResponseEntity.ok(callSessionService.endCall(callId));
    }

    @GetMapping("/agent/{agentId}")
    @PreAuthorize("hasAuthority('SCOPE_call:read')")
    public ResponseEntity<List<CallSession>> getAgentCalls(
            @PathVariable Long agentId,
            @RequestParam(required = false) CallSession.CallStatus status) {
        if (status != null) {
            return ResponseEntity.ok(callSessionService.findByAgentAndStatus(agentId, status));
        }
        return ResponseEntity.ok(callSessionService.findActiveCallsByAgent(agentId));
    }

    /**
     * Receive incoming call notification from PBX Core
     */
    @PostMapping("/incoming")
    public ResponseEntity<CallSession> handleIncomingCall(@RequestBody Map<String, Object> request) {
        String callId = (String) request.get("callId");
        Long agentId = Long.parseLong(request.get("agentId").toString());
        String tenantId = (String) request.get("tenantId");
        String from = (String) request.get("from");
        String to = (String) request.get("to");

        CallSession session = callSessionService.handleIncomingCall(callId, agentId, tenantId, from, to);
        return ResponseEntity.ok(session);
    }

    /**
     * Initiate outbound call
     */
    @PostMapping("/outbound")
    @PreAuthorize("hasAuthority('SCOPE_call:write')")
    public ResponseEntity<CallSession> initiateOutboundCall(@RequestBody Map<String, Object> request) {
        Long agentId = Long.parseLong(request.get("agentId").toString());
        String destination = (String) request.get("destination");

        CallSession session = callSessionService.initiateOutboundCall(agentId, destination);
        return ResponseEntity.ok(session);
    }
}
