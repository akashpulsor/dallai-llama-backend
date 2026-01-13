package com.dalai.llama.agent.events;


import com.dalai.llama.agent.service.PresenceStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Slf4j
@Component
@RequiredArgsConstructor
public class CallEventsListener {

    private final PresenceStore presenceStore;
    private final ObjectMapper om = new ObjectMapper();
    private final TaskScheduler scheduler;

    private static final long ACW_SECONDS = 30;

    @KafkaListener(
            topics = "${app.kafka.topics.call-events}",
            groupId = "${spring.kafka.consumer.group-id}"
    )
    public void handleCallEvent(String message) {

        try {
            JsonNode json = om.readTree(message);

            String tenantId = json.path("tenantId").asText(null);
            Long agentId = json.path("agentId").asLong(0);
            String callId = json.path("callId").asText(null);
            String event = json.path("eventType").asText(null);

            if (tenantId == null || agentId == 0L || event == null) {
                log.warn("Invalid call event: {}", message);
                return;
            }

            switch (event) {

                case "dialog.start":
                    onCallStarted(tenantId, agentId, callId);
                    break;

                case "dialog.end":
                    onCallEnded(tenantId, agentId);
                    break;

                default:
                    log.debug("Ignoring event {}", event);
            }

        } catch (Exception e) {
            log.error("Failed to parse call event {}", message, e);
        }
    }

    private void onCallStarted(String tenantId, Long agentId, String callId) {
        log.info("CALL_STARTED tenant={} agent={} call={}", tenantId, agentId, callId);

        presenceStore.setCallState(tenantId, agentId, "CALL_STARTED");
        presenceStore.setCurrentCall(tenantId, agentId, callId);

        // Set user to BUSY
        presenceStore.setDesiredAvailability(tenantId, agentId, "BUSY");
    }

    private void onCallEnded(String tenantId, Long agentId) {
        log.info("CALL_ENDED tenant={} agent={}", tenantId, agentId);

        presenceStore.setCallState(tenantId, agentId, "CALL_ENDED");

        // Enter ACW state immediately
        presenceStore.setDesiredAvailability(tenantId, agentId, "ACW");

        Instant acwEnd = Instant.now().plusSeconds(ACW_SECONDS);
        presenceStore.setAcwUntil(tenantId, agentId, acwEnd);

        // Schedule transition to AVAILABLE after ACW
        scheduler.schedule(() -> endAcw(tenantId, agentId), acwEnd);
    }

    private void endAcw(String tenantId, Long agentId) {
        log.info("ACW_FINISHED tenant={} agent={}", tenantId, agentId);

        presenceStore.clearCallState(tenantId, agentId);
        presenceStore.clearAcw(tenantId, agentId);

        // Return agent to AVAILABLE (which becomes LIVE if sipRegistered=true)
        presenceStore.setDesiredAvailability(tenantId, agentId, "AVAILABLE");
    }
}
