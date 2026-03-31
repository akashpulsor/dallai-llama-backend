package com.dalai.llama.pbx.core.esl;


import com.dalai.llama.pbx.core.domain.enums.AgentStatus;
import com.dalai.llama.pbx.core.redis.ActiveCallTracker;
import com.dalai.llama.pbx.core.redis.ChannelCounterService;
import com.dalai.llama.pbx.core.repository.core.AgentRepository;
import com.dalai.llama.pbx.core.repository.integration.CrmIntegrationRepository;
import com.dalai.llama.pbx.core.service.integration.CrmService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import com.dalai.llama.pbx.core.domain.enums.ContactStatus;
import com.dalai.llama.pbx.core.repository.campaign.CampaignContactRepository;
import com.dalai.llama.pbx.core.repository.campaign.CampaignRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

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

 * Subscribes to FreeSWITCH events and dispatches to:
 *   1. ActiveCallTracker (Redis) — real-time call state
 *   2. WebSocket (STOMP) — Agent/Supervisor UI notifications
 *   3. AgentRepository — agent status transitions
 *   4. ChannelCounterService — backup decrement on CHANNEL_HANGUP
 *   5. CampaignContactRepository — outbound campaign contact tracking
 *
 * Events subscribed:
 *   CHANNEL_CREATE  → track new call
 *   CHANNEL_ANSWER  → update call status + agent status → ON_CALL + campaign contact → CONNECTED
 *   CHANNEL_HANGUP  → remove call + agent status → WRAP_UP + campaign contact → COMPLETED/RETRY/FAILED
 *   CHANNEL_BRIDGE  → track bridged legs
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
    private final CampaignContactRepository campaignContactRepository;
    private final CampaignRepository campaignRepository;
    private final CrmIntegrationRepository crmIntegrationRepository;
    private final CrmService crmService;
    private static final String SUBSCRIBED_EVENTS =
            "CHANNEL_CREATE CHANNEL_ANSWER CHANNEL_HANGUP CHANNEL_BRIDGE " +
                    "DTMF RECORD_START RECORD_STOP CUSTOM";

    private static final Set<String> RETRYABLE_CAUSES = Set.of(
            "NO_ANSWER", "USER_BUSY", "ORIGINATOR_CANCEL",
            "NORMAL_TEMPORARY_FAILURE", "RECOVERY_ON_TIMER_EXPIRE"
    );

    private static final Set<String> PERMANENT_FAIL_CAUSES = Set.of(
            "UNALLOCATED_NUMBER", "CALL_REJECTED", "NUMBER_CHANGED",
            "INVALID_NUMBER_FORMAT", "DESTINATION_OUT_OF_ORDER"
    );

    @PostConstruct
    public void init() {
        startEventLoop();
    }

    @Async("eslEventExecutor")
    public void startEventLoop() {
        EslClient client = connectionManager.getClient();

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
            client.subscribeEvents(SUBSCRIBED_EVENTS);
            log.info("ESL event listener started — subscribed to: {}", SUBSCRIBED_EVENTS);

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
        startEventLoop();
    }

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

    // ═══════════════════════════════════════════════════════════
    // CHANNEL_CREATE
    // ═══════════════════════════════════════════════════════════

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

    // ═══════════════════════════════════════════════════════════
    // CHANNEL_ANSWER
    // ═══════════════════════════════════════════════════════════

    private void handleChannelAnswer(Map<String, String> h, String callId, String tenantId) {
        if (callId == null) return;

        callTracker.updateStatus(callId, "ANSWERED");

        // Agent status → ON_CALL
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

        // ── Campaign contact tracking ──
        handleCampaignAnswer(h);
    }

    // ═══════════════════════════════════════════════════════════
    // CHANNEL_HANGUP
    // ═══════════════════════════════════════════════════════════

    private void handleChannelHangup(Map<String, String> h, String callId, String tenantId) {
        if (callId == null) return;

        String hangupCause = h.getOrDefault("Hangup-Cause", "NORMAL_CLEARING");

        // Remove from active call tracker
        if (tenantId != null) {
            UUID tid = UUID.fromString(tenantId);
            callTracker.removeCall(callId, tid);

            String direction = h.getOrDefault("Call-Direction", "inbound");
            if ("inbound".equalsIgnoreCase(direction)) {
                channelCounter.decrementInbound(tid);
            } else {
                channelCounter.decrementOutbound(tid);
            }
        }

        // Agent status → WRAP_UP
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

        // ── Campaign contact tracking ──
        handleCampaignHangup(h, hangupCause);

        if (tenantId != null) {
            publishToTenant(tenantId, "calls", Map.of(
                    "event", "CALL_ENDED",
                    "call_id", callId,
                    "hangup_cause", hangupCause
            ));
        }
    }

    // ═══════════════════════════════════════════════════════════
    // CAMPAIGN CONTACT TRACKING
    // ═══════════════════════════════════════════════════════════

    /**
     * When an outbound campaign call is answered, update contact → CONNECTED.
     * Channel variables campaign_id and contact_id were set by DialerEngine.originate().
     */
    private void handleCampaignAnswer(Map<String, String> h) {
        String campaignId = h.get("variable_campaign_id");
        String contactId = h.get("variable_contact_id");
        if (campaignId == null || contactId == null) return;

        try {
            campaignContactRepository.findById(UUID.fromString(contactId)).ifPresent(contact -> {
                contact.setStatus(ContactStatus.CONNECTED);
                contact.setAnsweredAt(Instant.now());
                campaignContactRepository.save(contact);
                campaignRepository.incrementContactsConnected(UUID.fromString(campaignId));
                log.info("Campaign contact answered: campaign={} contact={} phone={}",
                        campaignId, contactId, contact.getPhoneNumber());
            });
        } catch (Exception e) {
            log.error("Campaign answer tracking error: campaign={} contact={}: {}",
                    campaignId, contactId, e.getMessage());
        }
    }

    /**
     * When an outbound campaign call hangs up, update contact status + retry logic.
     *
     * Hangup cause mapping:
     *   NORMAL_CLEARING           → COMPLETED (bot finished or escalated successfully)
     *   NO_ANSWER / USER_BUSY     → RETRY (if attempts < max) or FAILED
     *   UNALLOCATED_NUMBER etc    → FAILED (permanent, don't retry)
     *
     * Retry: sets nextAttemptAt = now + retryDelayMinutes.
     * DialerEngine.findNextDialable() picks up RETRY contacts after nextAttemptAt.
     */
    private void handleCampaignHangup(Map<String, String> h, String hangupCause) {
        String campaignId = h.get("variable_campaign_id");
        String contactId = h.get("variable_contact_id");
        if (campaignId == null || contactId == null) return;

        try {
            campaignContactRepository.findById(UUID.fromString(contactId)).ifPresent(contact -> {
                contact.setHangupCause(hangupCause);
                contact.setCompletedAt(Instant.now());

                String billsec = h.get("variable_billsec");
                if (billsec != null) {
                    try {
                        contact.setDurationSeconds(Integer.parseInt(billsec));
                    } catch (NumberFormatException ignored) {}
                }

                if ("NORMAL_CLEARING".equals(hangupCause)) {
                    contact.setStatus(ContactStatus.COMPLETED);
                    campaignRepository.incrementContactsCompleted(UUID.fromString(campaignId));
                } else if (PERMANENT_FAIL_CAUSES.contains(hangupCause)) {
                    contact.setStatus(ContactStatus.FAILED);
                } else if (RETRYABLE_CAUSES.contains(hangupCause)) {
                    campaignRepository.findById(UUID.fromString(campaignId)).ifPresent(campaign -> {
                        int maxAttempts = campaign.getMaxAttemptsPerContact() != null
                                ? campaign.getMaxAttemptsPerContact() : 3;
                        int attempts = contact.getAttemptCount() != null ? contact.getAttemptCount() : 1;
                        if (attempts < maxAttempts) {
                            contact.setStatus(ContactStatus.RETRY);
                            int retryMinutes = campaign.getRetryDelayMinutes() != null
                                    ? campaign.getRetryDelayMinutes() : 60;
                            contact.setNextAttemptAt(Instant.now().plus(retryMinutes, ChronoUnit.MINUTES));
                            log.info("Campaign contact retry scheduled: phone={} attempt {}/{} in {}min",
                                    contact.getPhoneNumber(), attempts, maxAttempts, retryMinutes);
                        } else {
                            contact.setStatus(ContactStatus.FAILED);
                            log.info("Campaign contact exhausted retries: phone={} {}/{}",
                                    contact.getPhoneNumber(), attempts, maxAttempts);
                        }
                    });
                } else {
                    contact.setStatus(ContactStatus.FAILED);
                }

                campaignContactRepository.save(contact);
                log.info("Campaign call ended: campaign={} contact={} phone={} cause={} status={}",
                        campaignId, contactId, contact.getPhoneNumber(), hangupCause, contact.getStatus());

                // Publish to STOMP for live campaign dashboard
                String tenantId = h.get("variable_tenant_id");
                if (tenantId != null) {
                    publishToTenant(tenantId, "campaigns/" + campaignId, Map.of(
                            "event", "CONTACT_COMPLETED",
                            "contact_id", contactId,
                            "phone", contact.getPhoneNumber(),
                            "status", contact.getStatus().name(),
                            "hangup_cause", hangupCause
                    ));
                }

                // Auto-push call result to CRM
                if (contact.getCrmId() != null && contact.getCrmProvider() != null) {
                    String tid = h.get("variable_tenant_id");
                    if (tid != null) {
                        crmIntegrationRepository.findByTenantIdAndIsActiveTrue(UUID.fromString(tid)).stream()
                                .filter(crm -> crm.getProvider().equals(contact.getCrmProvider())
                                        && Boolean.TRUE.equals(crm.getSyncCalls()))
                                .findFirst()
                                .ifPresent(crm -> {
                                    try {
                                        crmService.pushCallResult(crm.getId(), contactId);
                                        log.info("CRM push: contact={} crm={}", contactId, crm.getProvider());
                                    } catch (Exception ex) {
                                        log.warn("CRM push failed: contact={} err={}", contactId, ex.getMessage());
                                    }
                                });
                    }
                }
            });
        } catch (Exception e) {
            log.error("Campaign hangup tracking error: campaign={} contact={}: {}",
                    campaignId, contactId, e.getMessage());
        }
    }
    // ═══════════════════════════════════════════════════════════
    // OTHER EVENTS
    // ═══════════════════════════════════════════════════════════

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