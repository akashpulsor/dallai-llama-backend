package com.dalai.llama.pbx.core.esl;

import com.dalai.llama.pbx.core.client.AiServiceClient;
import com.dalai.llama.pbx.core.domain.enums.AgentStatus;
import com.dalai.llama.pbx.core.redis.ActiveCallTracker;
import com.dalai.llama.pbx.core.redis.ChannelCounterService;
import com.dalai.llama.pbx.core.redis.RtpEngineConfigRedisService;
import com.dalai.llama.pbx.core.repository.core.AgentRepository;
import com.dalai.llama.pbx.core.repository.integration.CrmIntegrationRepository;
import com.dalai.llama.pbx.core.service.integration.CrmService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import com.dalai.llama.pbx.core.domain.enums.ContactStatus;
import com.dalai.llama.pbx.core.repository.campaign.CampaignContactRepository;
import com.dalai.llama.pbx.core.repository.campaign.CampaignRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import java.io.IOException;
import java.util.*;

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
    private final AiServiceClient aiServiceClient;
    private final RtpEngineConfigRedisService rtpEngineRedis;
    private final EslCommandExecutor eslCommandExecutor;

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
        Thread eslThread = new Thread(this::startEventLoop, "esl-event-loop");
        eslThread.setDaemon(true);
        eslThread.start();
    }

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

    // ═══════════════════════════════════════════════════════════
    // Everything below is UNCHANGED
    // ═══════════════════════════════════════════════════════════

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
        Map<String, String> trackData = new LinkedHashMap<>();
        trackData.put("direction", h.getOrDefault("Call-Direction", "inbound"));
        trackData.put("caller_number", h.getOrDefault("Caller-Caller-ID-Number", ""));
        trackData.put("callee_number", h.getOrDefault("Caller-Destination-Number", ""));
        trackData.put("status", "RINGING");

        // Store campaign/contact IDs for disposition writeback on AI escalation
        String campaignId = h.get("variable_campaign_id");
        String contactId = h.get("variable_contact_id");
        if (campaignId != null) trackData.put("campaign_id", campaignId);
        if (contactId != null) trackData.put("contact_id", contactId);

        callTracker.trackCall(callId, tid, trackData);

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

        handleCampaignAnswer(h);
    }

    private void handleChannelHangup(Map<String, String> h, String callId, String tenantId) {
        if (callId == null) return;

        String hangupCause = h.getOrDefault("Hangup-Cause", "NORMAL_CLEARING");

        // Deregister SSRC from ai-service before removing call from tracker
        deregisterSsrcIfActive(callId);

        if (tenantId != null) {
            UUID tid = UUID.fromString(tenantId);
            callTracker.removeCall(callId, tid);
            // Channel counter decrement is handled by KamailioController.callEnd()
            // — doing it here too would cause double-decrement and counter drift.
        }

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

        handleCampaignHangup(h, hangupCause);

        if (tenantId != null) {
            publishToTenant(tenantId, "calls", Map.of(
                    "event", "CALL_ENDED",
                    "call_id", callId,
                    "hangup_cause", hangupCause
            ));
        }
    }

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

    private void handleCampaignHangup(Map<String, String> h, String hangupCause) {
        String campaignId = h.get("variable_campaign_id");
        String contactId = h.get("variable_contact_id");
        if (campaignId == null || contactId == null) return;

        try {
            campaignContactRepository.findById(UUID.fromString(contactId)).ifPresent(contact -> {
                contact.setHangupCause(hangupCause);
                contact.setAttemptCount((contact.getAttemptCount() != null ? contact.getAttemptCount() : 0) + 1);
                contact.setLastAttemptAt(Instant.now());

                String billsec = h.get("variable_billsec");
                if (billsec != null) {
                    try {
                        contact.setDurationSeconds(Integer.parseInt(billsec));
                    } catch (NumberFormatException ignored) {}
                }

                // Don't overwrite qualification status set by AI bot escalation callback
                if (contact.getStatus() == ContactStatus.QUALIFIED
                        || contact.getStatus() == ContactStatus.NOT_QUALIFIED) {
                    contact.setCompletedAt(Instant.now());
                    campaignRepository.incrementContactsCompleted(UUID.fromString(campaignId));
                } else if ("NORMAL_CLEARING".equals(hangupCause)) {
                    contact.setStatus(ContactStatus.COMPLETED);
                    contact.setCompletedAt(Instant.now());
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

    private void handleChannelBridge(Map<String, String> h, String callId, String tenantId) {
        if (callId == null || tenantId == null) return;
        String otherUuid = h.get("Other-Leg-Unique-ID");
        callTracker.updateField(callId, "bridged_to", otherUuid != null ? otherUuid : "");

        publishToTenant(tenantId, "calls", Map.of(
                "event", "CALL_BRIDGED",
                "call_id", callId,
                "other_leg", otherUuid != null ? otherUuid : ""
        ));

        // AI fork: register SSRC with ai-service on agent bridge
        registerSsrcIfForkEnabled(callId, tenantId, otherUuid);
    }

    /**
     * Register SSRC pair with ai-service so it can correlate forked RTP packets.
     *
     * SSRC (Synchronization Source) is a 32-bit identifier in every RTP packet header.
     * RTPEngine forks media to ai-service, but ai-service needs to know which
     * tenant/call each RTP stream belongs to. This mapping provides that.
     *
     * Runs async (fire-and-forget) to avoid blocking the ESL event loop.
     */
    private void registerSsrcIfForkEnabled(String callId, String tenantId, String otherUuid) {
        try {
            UUID tid = UUID.fromString(tenantId);

            // Check if this call should have AI fork active
            boolean forkEnabled = rtpEngineRedis.isAiForkEnabled(tid);
            if (!forkEnabled) return;

            // For escalated calls, also check the pending flag
            Optional<Map<Object, Object>> callDetail = callTracker.getCallDetail(callId);
            boolean isEscalation = callDetail
                    .map(d -> "true".equals(d.get("ai_fork_pending")))
                    .orElse(false);

            // Extract SSRC from FreeSWITCH channel variables via ESL
            String ssrcCaller = eslCommandExecutor.getVariable(callId, "rtp_remote_ssrc");
            String ssrcAgent = otherUuid != null
                    ? eslCommandExecutor.getVariable(otherUuid, "rtp_local_ssrc")
                    : h(callId, "rtp_local_ssrc");

            // Register with ai-service (fire-and-forget, fail-open)
            aiServiceClient.registerRtpSession(callId, tid, ssrcCaller, ssrcAgent);

            // Track that we registered this call for cleanup on hangup
            callTracker.updateField(callId, "ai_fork_active", "true");
            if (isEscalation) {
                callTracker.updateField(callId, "ai_fork_pending", "false");
            }

            log.info("SSRC registered: callId={} tenant={} caller_ssrc={} agent_ssrc={} escalation={}",
                    callId, tenantId, ssrcCaller, ssrcAgent, isEscalation);
        } catch (Exception e) {
            // FAIL-OPEN: SSRC registration failure must NOT block the call
            log.warn("SSRC registration failed for callId={}: {}", callId, e.getMessage());
        }
    }

    private String h(String callId, String varName) {
        try {
            return eslCommandExecutor.getVariable(callId, varName);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Deregister SSRC from ai-service on call hangup.
     * Only fires if ai_fork_active was set during CHANNEL_BRIDGE.
     * Fail-open: deregistration failure is logged but does not affect hangup.
     */
    private void deregisterSsrcIfActive(String callId) {
        try {
            Optional<Map<Object, Object>> callDetail = callTracker.getCallDetail(callId);
            boolean forkActive = callDetail
                    .map(d -> "true".equals(d.get("ai_fork_active")))
                    .orElse(false);
            if (!forkActive) return;

            aiServiceClient.deregisterRtpSession(callId);
            log.info("SSRC deregistered on hangup: callId={}", callId);
        } catch (Exception e) {
            log.warn("SSRC deregistration failed for callId={}: {}", callId, e.getMessage());
        }
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