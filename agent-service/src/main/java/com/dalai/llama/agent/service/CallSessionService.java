package com.dalai.llama.agent.service;

import com.dalai.llama.agent.entity.CallSession;
import com.dalai.llama.agent.events.AgentEventsProducer;
import com.dalai.llama.agent.repository.CallSessionRepository;
import com.dalai.llama.agent.sip.SipClientService;
import com.dalai.llama.agent.webrtc.WebRtcSignalingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class CallSessionService {

    private final CallSessionRepository callSessionRepository;
    private final AgentEventsProducer eventsProducer;
    private final AgentEventLogService eventLogService;
    private final SipClientService sipClientService;
    private final WebRtcSignalingService webRtcSignalingService;

    @Transactional
    public CallSession createCallSession(CallSession callSession) {
        log.info("Creating call session: {} for agent: {}", callSession.getCallId(), callSession.getAgentId());
        CallSession saved = callSessionRepository.save(callSession);
        eventsProducer.publishCallEvent(saved.getTenantId(), "CALL_CREATED", saved);
        eventLogService.logEvent(saved.getAgentId(), saved.getId(), "CALL_CREATED",
                "New call session created", null);
        return saved;
    }

    @Transactional
    public CallSession answerCall(String callId) {
        Optional<CallSession> callOpt = callSessionRepository.findByCallId(callId);
        if (callOpt.isEmpty()) {
            throw new RuntimeException("Call not found: " + callId);
        }

        CallSession call = callOpt.get();
        call.answer();
        CallSession saved = callSessionRepository.save(call);

        // Trigger actual call answering via SIP
        sipClientService.answerCall(saved);

        // Notify via WebRTC if agent is connected
        if (webRtcSignalingService.isAgentConnected(saved.getAgentId().toString())) {
            // WebRTC flow will be handled by WebSocket signaling
            log.info("Agent {} using WebRTC for call {}", saved.getAgentId(), callId);
        }

        eventsProducer.publishCallEvent(saved.getTenantId(), "CALL_ANSWERED", saved);
        eventLogService.logEvent(saved.getAgentId(), saved.getId(), "CALL_ANSWERED",
                "Call answered", null);

        log.info("Call {} answered by agent {}", callId, call.getAgentId());
        return saved;
    }

    @Transactional
    public CallSession endCall(String callId) {
        Optional<CallSession> callOpt = callSessionRepository.findByCallId(callId);
        if (callOpt.isEmpty()) {
            throw new RuntimeException("Call not found: " + callId);
        }

        CallSession call = callOpt.get();
        call.end();
        CallSession saved = callSessionRepository.save(call);

        // Trigger actual call termination via SIP
        sipClientService.endCall(saved);

        // Notify WebRTC client if connected
        webRtcSignalingService.notifyCallEnded(saved.getAgentId().toString(), callId);

        eventsProducer.publishCallEvent(saved.getTenantId(), "CALL_ENDED", saved);
        eventLogService.logEvent(saved.getAgentId(), saved.getId(), "CALL_ENDED",
                "Call ended", null);

        log.info("Call {} ended. Duration: {}s", callId, call.getTotalDuration());
        return saved;
    }

    public Optional<CallSession> findByCallId(String callId) {
        return callSessionRepository.findByCallId(callId);
    }

    public List<CallSession> findActiveCallsByAgent(Long agentId) {
        return callSessionRepository.findActiveCallsByAgent(agentId);
    }

    public List<CallSession> findByAgentAndStatus(Long agentId, CallSession.CallStatus status) {
        return callSessionRepository.findByAgentIdAndStatus(agentId, status);
    }

    /**
     * Handle incoming call from PBX - notifies agent
     */
    @Transactional
    public CallSession handleIncomingCall(String callId, Long agentId, String tenantId,
                                          String from, String to) {
        log.info("Handling incoming call {} for agent {}", callId, agentId);

        CallSession callSession = new CallSession();
        callSession.setCallId(callId);
        callSession.setAgentId(agentId);
        callSession.setTenantId(tenantId);
        callSession.setDirection(CallSession.Direction.INBOUND);
        callSession.setFrom(from);
        callSession.setTo(to);
        callSession.setStatus(CallSession.CallStatus.RINGING);

        CallSession saved = callSessionRepository.save(callSession);

        // Notify agent via WebRTC if connected
        if (webRtcSignalingService.isAgentConnected(agentId.toString())) {
            webRtcSignalingService.notifyIncomingCall(agentId.toString(), saved);
        }

        eventsProducer.publishCallEvent(tenantId, "CALL_INCOMING", saved);
        eventLogService.logEvent(agentId, saved.getId(), "CALL_INCOMING",
                "Incoming call received", null);

        return saved;
    }

    /**
     * Initiate outbound call from agent
     */
    @Transactional
    public CallSession initiateOutboundCall(Long agentId, String destination) {
        log.info("Initiating outbound call from agent {} to {}", agentId, destination);

        // TODO: Get agent details from repository
        // Agent agent = agentRepository.findById(agentId).orElseThrow();

        // For now, create a placeholder session
        // In production, sipClientService.initiateOutboundCall will return proper session
        CallSession callSession = new CallSession();
        callSession.setCallId("out-" + System.currentTimeMillis());
        callSession.setAgentId(agentId);
        callSession.setDirection(CallSession.Direction.OUTBOUND);
        callSession.setTo(destination);
        callSession.setStatus(CallSession.CallStatus.RINGING);

        CallSession saved = callSessionRepository.save(callSession);

        eventsProducer.publishCallEvent(saved.getTenantId(), "CALL_OUTBOUND", saved);
        eventLogService.logEvent(agentId, saved.getId(), "CALL_OUTBOUND",
                "Outbound call initiated", null);

        return saved;
    }
}
