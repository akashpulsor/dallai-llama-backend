package com.dalai.llama.agent.events;




import com.dalai.llama.agent.service.PresenceStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class RegistrationEventsListener {

    private final PresenceStore presenceStore;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Kamailio emits REGISTER / UNREGISTER events.
     * REGISTER  → agent becomes LIVE
     * UNREGISTER or expires=0 → agent becomes OFFLINE
     */
    @KafkaListener(
            topics = "${app.kafka.topics.reg-events}",
            groupId = "${app.kafka.consumer-group}"
    )
    public void handleRegistrationEvent(String message) {
        try {
            JsonNode json = objectMapper.readTree(message);

            String tenantId = json.path("tenantId").asText(null);
            String aor = json.path("aor").asText(null); // SIP username (agent extension)
            String contact = json.path("contact").asText(null);
            int expires = json.path("expires").asInt(0);
            String eventType = json.path("eventType").asText(null);

            if (tenantId == null || aor == null || eventType == null) {
                log.warn("Invalid registration event: {}", message);
                return;
            }

            // ------------------------------
            // SIP REGISTER → agent becomes LIVE
            // ------------------------------
            if ("REGISTER".equalsIgnoreCase(eventType)) {
                log.info("REGISTRATION: tenant={} aor={} contact={} expires={}",
                        tenantId, aor, contact, expires);

                presenceStore.updateContact(tenantId, aor, contact, expires);

                // OPTIONAL: restore AVAILABLE only if not in ACW or BUSY
                var state = presenceStore.getPresence(tenantId,
                        presenceStore.lookupAgentIdInternal(tenantId, aor));

                String current = state.get("effectiveAvailability");
                if (!"BUSY".equalsIgnoreCase(current) &&
                        !"ACW".equalsIgnoreCase(current)) {

                    presenceStore.setDesiredAvailability(
                            tenantId,
                            presenceStore.lookupAgentIdInternal(tenantId, aor),
                            "AVAILABLE"
                    );
                }
                return;
            }

            // ------------------------------
            // SIP UNREGISTER / expires=0 → agent OFFLINE
            // ------------------------------
            if ("UNREGISTER".equalsIgnoreCase(eventType) || expires == 0) {

                log.info("UNREGISTER: tenant={} aor={}", tenantId, aor);

                presenceStore.clearContact(tenantId, aor);

                Long agentId = presenceStore.lookupAgentIdInternal(tenantId, aor);
                if (agentId != null) {
                    presenceStore.setOffline(tenantId, agentId);
                    presenceStore.clearCallState(tenantId, agentId);
                    presenceStore.clearAcw(tenantId, agentId);
                }

                return;
            }

            log.debug("Ignoring unknown registration eventType={} msg={}", eventType, message);

        } catch (Exception ex) {
            log.error("Failed to process registration event: {}", message, ex);
        }
    }
}
