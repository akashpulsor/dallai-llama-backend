package com.dalai.llama.pbx.core.websocket;



import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Typed publisher for STOMP WebSocket events.
 *
 * All events are tenant-scoped:
 *   /topic/tenant/{tenantId}/calls    → call lifecycle events
 *   /topic/tenant/{tenantId}/agents   → agent status changes
 *   /topic/tenant/{tenantId}/queues   → queue stats updates
 *   /topic/tenant/{tenantId}/campaigns → campaign dialer events
 *
 * Called by:
 *   - EslEventListener       → call events + agent status (CHANNEL_ANSWER, CHANNEL_HANGUP)
 *   - AgentService            → agent login/logout/break
 *   - QueueService            → queue stats changes
 *   - DialerEngine            → campaign contact dialed/connected/completed
 *   - SupervisorService       → supervisor actions (listen/whisper/barge started)
 *
 * Each publish is wrapped in try-catch — a failed WebSocket push should NEVER
 * break the call flow or ESL event processing.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketEventPublisher {

    private final SimpMessagingTemplate messaging;

    // ═══════════════════════════════════════════════════════════
    // CALL EVENTS → /topic/tenant/{tenantId}/calls
    // ═══════════════════════════════════════════════════════════

    public void callRinging(UUID tenantId, String callId, String callerNumber,
                            String calleeNumber, String direction) {
        publish(tenantId, "calls", Map.of(
                "event", "CALL_RINGING",
                "call_id", callId,
                "caller_number", callerNumber != null ? callerNumber : "",
                "callee_number", calleeNumber != null ? calleeNumber : "",
                "direction", direction != null ? direction : "INBOUND"
        ));
    }

    public void callAnswered(UUID tenantId, String callId, String agentId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", "CALL_ANSWERED");
        payload.put("call_id", callId);
        if (agentId != null) payload.put("agent_id", agentId);
        publish(tenantId, "calls", payload);
    }

    public void callEnded(UUID tenantId, String callId, String hangupCause, Integer durationSeconds) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", "CALL_ENDED");
        payload.put("call_id", callId);
        payload.put("hangup_cause", hangupCause != null ? hangupCause : "NORMAL_CLEARING");
        if (durationSeconds != null) payload.put("duration_seconds", durationSeconds);
        publish(tenantId, "calls", payload);
    }

    public void callTransferred(UUID tenantId, String callId, String destination) {
        publish(tenantId, "calls", Map.of(
                "event", "CALL_TRANSFERRED",
                "call_id", callId,
                "destination", destination != null ? destination : ""
        ));
    }

    public void callHeld(UUID tenantId, String callId, boolean held) {
        publish(tenantId, "calls", Map.of(
                "event", held ? "CALL_HELD" : "CALL_UNHELD",
                "call_id", callId
        ));
    }

    public void callBridged(UUID tenantId, String callId, String otherLeg) {
        publish(tenantId, "calls", Map.of(
                "event", "CALL_BRIDGED",
                "call_id", callId,
                "other_leg", otherLeg != null ? otherLeg : ""
        ));
    }

    public void dtmfReceived(UUID tenantId, String callId, String digit) {
        publish(tenantId, "calls", Map.of(
                "event", "DTMF",
                "call_id", callId,
                "digit", digit
        ));
    }

    // ═══════════════════════════════════════════════════════════
    // AGENT EVENTS → /topic/tenant/{tenantId}/agents
    // ═══════════════════════════════════════════════════════════

    public void agentStatusChanged(UUID tenantId, UUID agentId, String newStatus) {
        publish(tenantId, "agents", Map.of(
                "event", "AGENT_STATUS_CHANGED",
                "agent_id", agentId.toString(),
                "status", newStatus
        ));
    }

    public void agentLoggedIn(UUID tenantId, UUID agentId, String displayName) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", "AGENT_LOGGED_IN");
        payload.put("agent_id", agentId.toString());
        if (displayName != null) payload.put("display_name", displayName);
        publish(tenantId, "agents", payload);
    }

    public void agentLoggedOut(UUID tenantId, UUID agentId) {
        publish(tenantId, "agents", Map.of(
                "event", "AGENT_LOGGED_OUT",
                "agent_id", agentId.toString()
        ));
    }

    // ═══════════════════════════════════════════════════════════
    // QUEUE EVENTS → /topic/tenant/{tenantId}/queues
    // ═══════════════════════════════════════════════════════════

    public void queueStatsUpdated(UUID tenantId, UUID queueId, int waitingCalls,
                                  int availableAgents, int longestWaitSeconds) {
        publish(tenantId, "queues", Map.of(
                "event", "QUEUE_STATS_UPDATED",
                "queue_id", queueId.toString(),
                "waiting_calls", waitingCalls,
                "available_agents", availableAgents,
                "longest_wait_seconds", longestWaitSeconds
        ));
    }

    public void callEnteredQueue(UUID tenantId, UUID queueId, String callId) {
        publish(tenantId, "queues", Map.of(
                "event", "CALL_ENTERED_QUEUE",
                "queue_id", queueId.toString(),
                "call_id", callId
        ));
    }

    public void callLeftQueue(UUID tenantId, UUID queueId, String callId, String reason) {
        publish(tenantId, "queues", Map.of(
                "event", "CALL_LEFT_QUEUE",
                "queue_id", queueId.toString(),
                "call_id", callId,
                "reason", reason
        ));
    }

    // ═══════════════════════════════════════════════════════════
    // CAMPAIGN EVENTS → /topic/tenant/{tenantId}/campaigns
    // ═══════════════════════════════════════════════════════════

    public void contactDialed(UUID tenantId, UUID campaignId, String phoneNumber) {
        publish(tenantId, "campaigns", Map.of(
                "event", "CONTACT_DIALED",
                "campaign_id", campaignId.toString(),
                "phone_number", phoneNumber
        ));
    }

    // ADD this public method (one-liner that delegates to private publish)
    public void publishRaw(UUID tenantId, String channel, Map<String, Object> payload) {
        publish(tenantId, channel, payload);
    }
    public void contactConnected(UUID tenantId, UUID campaignId, String phoneNumber) {
        publish(tenantId, "campaigns", Map.of(
                "event", "CONTACT_CONNECTED",
                "campaign_id", campaignId.toString(),
                "phone_number", phoneNumber
        ));
    }

    public void campaignCompleted(UUID tenantId, UUID campaignId, String name) {
        publish(tenantId, "campaigns", Map.of(
                "event", "CAMPAIGN_COMPLETED",
                "campaign_id", campaignId.toString(),
                "name", name
        ));
    }

    // ═══════════════════════════════════════════════════════════
    // INTERNAL — safe publish
    // ═══════════════════════════════════════════════════════════

    private void publish(UUID tenantId, String channel, Map<String, Object> payload) {
        try {
            Map<String, Object> envelope = new LinkedHashMap<>(payload);
            envelope.put("timestamp", Instant.now().toString());

            messaging.convertAndSend(
                    "/topic/tenant/" + tenantId + "/" + channel,
                    envelope
            );
            log.trace("WS → /topic/tenant/{}/{} event={}", tenantId, channel, payload.get("event"));
        } catch (Exception e) {
            // Never let a WebSocket failure break the call flow
            log.warn("WebSocket publish failed for tenant={} channel={}: {}",
                    tenantId, channel, e.getMessage());
        }
    }
}