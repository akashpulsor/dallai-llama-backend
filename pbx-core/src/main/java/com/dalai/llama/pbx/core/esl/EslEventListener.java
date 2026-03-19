package com.dalai.llama.pbx.core.esl;


import com.dalai.llama.pbx.core.domain.enums.AgentStatus;
import com.dalai.llama.pbx.core.redis.ActiveCallTracker;
import com.dalai.llama.pbx.core.redis.ChannelCounterService;
import com.dalai.llama.pbx.core.repository.core.AgentRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.*;

/**
 * Subscribes to FreeSWITCH events and dispatches to:
 *   1. ActiveCallTracker (Redis) — real-time call state
 *   2. WebSocket (STOMP) — Agent/Supervisor UI notifications
 *   3. AgentRepository — agent status transitions
 *   4. ChannelCounterService — backup decrement on CHANNEL_HANGUP
 *
 * Runs in a dedicated thread (eslEventExecutor from AsyncConfig).
 * On disconnect, notifies EslConnectionManager for auto-reconnect.
 *
 * Events subscribed:
 *   CHANNEL_CREATE  → track new call
 *   CHANNEL_ANSWER  → update call status + agent status → ON_CALL
 *   CHANNEL_HANGUP  → remove call + agent status → WRAP_UP → ONLINE
 *   DTMF            → publish to WebSocket (IVR tracking)
 *   RECORD_START    → update call detail with recording path
 *   RECORD_STOP     → finalize recording URL
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EslEventListener {

    private final EslConnectionManager connectionManager;
    private final ActiveCallTracker callTracker;
    private final ChannelCounterService channelCounter;
    private final AgentRepository agentRepository;
    private final SimpMessagingTemplate messagingTemplate;

    private static final String SUBSCRIBED_EVENTS =
            "CHANNEL_CREATE CHANNEL_ANSWER CHANNEL_HANGUP CHANNEL_BRIDGE " +
                    "DTMF RECORD_START RECORD_STOP CUSTOM";

    @PostConstruct
    public void init() {
        startEventLoop();
    }

    /**
     * Start the event loop in a separate thread.
     * Subscribes to events then enters blocking read loop.
     */
    @Async("eslEventExecutor")
    public void startEventLoop() {
        EslClient client = connectionManager.getClient();

        // Wait for connection
        int retries = 0;
        while (!client.isConnected() && retries < 30) {
            try {
                Thread.sleep(1000);
                retries++;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }

        if (!client.isConnected()) {
            log.error("ESL event listener: connection not available after {}s", retries);
            return;
        }

        try {
            // Subscribe to events
            client.subscribeEvents(SUBSCRIBED_EVENTS);
            log.info("ESL event listener started — subscribed to: {}", SUBSCRIBED_EVENTS);

            // Blocking event loop
            while (client.isConnected()) {
                String event = client.readEvent();
                if (event != null && !event.isBlank()) {
                    processEvent(event);
                }
            }
        } catch (IOException e) {
            log.error("ESL event loop error: {}", e.getMessage());
        }

        log.warn("ESL event loop ended — triggering reconnect");
        connectionManager.onDisconnect();

        // Restart event loop after reconnect
        startEventLoop();
    }

    /**
     * Parse and dispatch a single ESL event.
     */
    private void processEvent(String rawEvent) {
        try {
            Map<String, String> headers = parseHeaders(rawEvent);
            String eventName = headers.get("Event-Name");
            if (eventName == null) return;

            String callId = headers.get("Unique-ID");
            String tenantId = headers.get("variable_tenant_id");

            switch (eventName) {
                case "CHANNEL_CREATE" -> handleChannelCreate(headers, callId, tenantId);
                case "CHANNEL_ANSWER" -> handleChannelAnswer(headers, callId, tenantId);
                case "CHANNEL_HANGUP" -> handleChannelHangup(headers, callId, tenantId);
                case "CHANNEL_BRIDGE" -> handleChannelBridge(headers, callId, tenantId);
                case "DTMF"           -> handleDtmf(headers, callId, tenantId);
                case "RECORD_START"   -> handleRecordStart(headers, callId);
                case "RECORD_STOP"    -> handleRecordStop(headers, callId);
                default -> log.trace("ESL event ignored: {}", eventName);
            }
        } catch (Exception e) {
            log.error("Error processing ESL event: {}", e.getMessage(), e);
        }
    }

    private void handleChannelCreate(Map<String, String> h, String callId, String tenantId) {
        if (callId == null || tenantId == null) return;

        UUID tid = UUID.fromString(tenantId);
        callTracker.trackCall(callId, tid, Map.of(
                "direction", h.getOrDefault("Call-Direction", "inbound"),
                "caller_number", h.getOrDefault("Caller-Caller-ID-Number", ""),
                "callee_number", h.getOrDefault("Caller-Destination-Number", ""),
                "status", "RINGING"
        ));

        publishToTenant(tenantId, "calls", Map.of(
                "event", "CALL_RINGING",
                "call_id", callId,
                "caller", h.getOrDefault("Caller-Caller-ID-Number", ""),
                "callee", h.getOrDefault("Caller-Destination-Number", "")
        ));
    }

    private void handleChannelAnswer(Map<String, String> h, String callId, String tenantId) {
        if (callId == null) return;

        callTracker.updateStatus(callId, "ANSWERED");

        // If this is an agent leg, update agent status to ON_CALL
        String agentUsername = h.get("variable_effective_caller_id_number");
        if (agentUsername != null && tenantId != null) {
            UUID tid = UUID.fromString(tenantId);
            agentRepository.findByTenantIdAndUsername(tid, agentUsername)
                    .ifPresent(agent -> {
                        agentRepository.updateStatus(agent.getId(), AgentStatus.ON_CALL);
                        callTracker.setAgentId(callId, agent.getId().toString());

                        publishToTenant(tenantId, "agents", Map.of(
                                "event", "AGENT_STATUS_CHANGED",
                                "agent_id", agent.getId().toString(),
                                "status", "ON_CALL"
                        ));
                    });
        }

        if (tenantId != null) {
            publishToTenant(tenantId, "calls", Map.of(
                    "event", "CALL_ANSWERED",
                    "call_id", callId
            ));
        }
    }

    private void handleChannelHangup(Map<String, String> h, String callId, String tenantId) {
        if (callId == null) return;

        String hangupCause = h.getOrDefault("Hangup-Cause", "NORMAL_CLEARING");

        // Remove from active call tracker
        if (tenantId != null) {
            UUID tid = UUID.fromString(tenantId);
            callTracker.removeCall(callId, tid);

            // Backup channel counter decrement (in case Kamailio event was missed)
            String direction = h.getOrDefault("Call-Direction", "inbound");
            if ("inbound".equalsIgnoreCase(direction)) {
                channelCounter.decrementInbound(tid);
            } else {
                channelCounter.decrementOutbound(tid);
            }
        }

        // If agent was on this call, transition to WRAP_UP
        String agentUsername = h.get("variable_effective_caller_id_number");
        if (agentUsername != null && tenantId != null) {
            UUID tid = UUID.fromString(tenantId);
            agentRepository.findByTenantIdAndUsername(tid, agentUsername)
                    .ifPresent(agent -> {
                        agentRepository.updateStatus(agent.getId(), AgentStatus.WRAP_UP);
                        publishToTenant(tenantId, "agents", Map.of(
                                "event", "AGENT_STATUS_CHANGED",
                                "agent_id", agent.getId().toString(),
                                "status", "WRAP_UP"
                        ));
                    });
        }

        if (tenantId != null) {
            publishToTenant(tenantId, "calls", Map.of(
                    "event", "CALL_ENDED",
                    "call_id", callId,
                    "hangup_cause", hangupCause
            ));
        }
    }

    private void handleChannelBridge(Map<String, String> h, String callId, String tenantId) {
        if (callId == null || tenantId == null) return;
        String otherUuid = h.get("Other-Leg-Unique-ID");
        callTracker.updateField(callId, "bridged_to", otherUuid != null ? otherUuid : "");

        publishToTenant(tenantId, "calls", Map.of(
                "event", "CALL_BRIDGED",
                "call_id", callId,
                "other_leg", otherUuid != null ? otherUuid : ""
        ));
    }

    private void handleDtmf(Map<String, String> h, String callId, String tenantId) {
        if (tenantId == null) return;
        publishToTenant(tenantId, "calls", Map.of(
                "event", "DTMF",
                "call_id", callId != null ? callId : "",
                "digit", h.getOrDefault("DTMF-Digit", "")
        ));
    }

    private void handleRecordStart(Map<String, String> h, String callId) {
        if (callId == null) return;
        String recordPath = h.getOrDefault("Record-File-Path", "");
        callTracker.updateField(callId, "recording_path", recordPath);
    }

    private void handleRecordStop(Map<String, String> h, String callId) {
        if (callId == null) return;
        String recordPath = h.getOrDefault("Record-File-Path", "");
        callTracker.updateField(callId, "recording_url", recordPath);
    }

    // ═══════════════════════════════════════════════════════════
    // WebSocket publish
    // ═══════════════════════════════════════════════════════════

    private void publishToTenant(String tenantId, String channel, Map<String, Object> payload) {
        try {
            messagingTemplate.convertAndSend(
                    "/topic/tenant/" + tenantId + "/" + channel,
                    payload
            );
        } catch (Exception e) {
            log.warn("Failed to publish WebSocket event: {}", e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════
    // ESL header parser
    // ═══════════════════════════════════════════════════════════

    private Map<String, String> parseHeaders(String rawEvent) {
        Map<String, String> headers = new HashMap<>();
        for (String line : rawEvent.split("\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && colon < line.length() - 1) {
                headers.put(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
            }
        }
        return headers;
    }
}