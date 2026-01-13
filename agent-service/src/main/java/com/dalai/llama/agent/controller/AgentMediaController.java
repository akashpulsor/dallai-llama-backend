package com.dalai.llama.agent.controller;

import com.dalai.llama.agent.dto.*;
import com.dalai.llama.agent.service.AgentMediaService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/agents/{agentId}/media")
@RequiredArgsConstructor
public class AgentMediaController {

    private final AgentMediaService mediaService;

    // ------------------- BARGE -------------------
    @PostMapping("/calls/{callId}/barge")
    public ResponseEntity<?> barge(
            @PathVariable String tenantId,
            @PathVariable Long agentId,
            @PathVariable String callId,
            @RequestBody BargeRequest request) {

        mediaService.barge(tenantId, agentId, callId, request);
        return ResponseEntity.accepted().build();
    }

    // ------------------- WHISPER -------------------
    @PostMapping("/calls/{callId}/whisper")
    public ResponseEntity<?> whisper(
            @PathVariable String tenantId,
            @PathVariable Long agentId,
            @PathVariable String callId,
            @RequestBody WhisperRequest request) {

        mediaService.whisper(tenantId, agentId, callId, request);
        return ResponseEntity.accepted().build();
    }

    // ------------------- MONITOR -------------------
    @PostMapping("/calls/{callId}/monitor")
    public ResponseEntity<?> monitor(
            @PathVariable String tenantId,
            @PathVariable Long agentId,
            @PathVariable String callId,
            @RequestBody MonitorRequest request) {

        mediaService.monitor(tenantId, agentId, callId, request);
        return ResponseEntity.accepted().build();
    }

    // ------------------- START CONFERENCE -------------------
    @PostMapping("/conferences")
    public ResponseEntity<ConferenceResponse> startConference(
            @PathVariable String tenantId,
            @PathVariable Long agentId,
            @RequestBody ConferenceRequest request) {

        return ResponseEntity.ok(mediaService.startConference(tenantId, agentId, request));
    }

    // ------------------- JOIN CONFERENCE -------------------
    @PostMapping("/conferences/{roomId}/join")
    public ResponseEntity<?> joinConference(
            @PathVariable String tenantId,
            @PathVariable Long agentId,
            @PathVariable String roomId,
            @RequestBody JoinConferenceRequest request) {

        mediaService.joinConference(tenantId, agentId, roomId, request);
        return ResponseEntity.accepted().build();
    }

    // ------------------- RECORDING -------------------
    @PostMapping("/calls/{callId}/recording/start")
    public ResponseEntity<?> startRecording(
            @PathVariable String tenantId,
            @PathVariable Long agentId,
            @PathVariable String callId) {

        mediaService.startRecording(tenantId, agentId, callId);
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/calls/{callId}/recording/stop")
    public ResponseEntity<?> stopRecording(
            @PathVariable String tenantId,
            @PathVariable Long agentId,
            @PathVariable String callId) {

        mediaService.stopRecording(tenantId, agentId, callId);
        return ResponseEntity.accepted().build();
    }
}
