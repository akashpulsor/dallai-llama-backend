package com.dalai.llama.pbx.core.controller.internal;


import com.dalai.llama.pbx.core.domain.entity.kamailio.Dispatcher;
import com.dalai.llama.pbx.core.domain.entity.kamailio.Subscriber;
import com.dalai.llama.pbx.core.domain.enums.CallDirection;
import com.dalai.llama.pbx.core.redis.ActiveCallTracker;
import com.dalai.llama.pbx.core.redis.ChannelCounterService;
import com.dalai.llama.pbx.core.redis.RtpEngineConfigRedisService;
import com.dalai.llama.pbx.core.repository.kamailio.DispatcherRepository;
import com.dalai.llama.pbx.core.repository.kamailio.SipDomainRepository;
import com.dalai.llama.pbx.core.repository.kamailio.SubscriberRepository;
import com.dalai.llama.pbx.core.service.auth.CallAuthorizationService;
import com.dalai.llama.pbx.core.service.call.CallControlService;
import com.dalai.llama.pbx.core.service.cdr.CdrService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * COMPLETE Kamailio integration controller.
 *
 * Kamailio has NO direct DB access. EVERY lookup comes through PBX-Core HTTP.
 * This controller is the ONLY interface between Kamailio and the telecom data.
 *
 * Endpoints by Kamailio call flow order:
 *
 *   ┌─────────────────────────────────────────────────────────────────────┐
 *   │ SIP REGISTER arrives at Kamailio                                    │
 *   │  1. POST /auth/digest      → get HA1 for digest validation         │
 *   │  2. Kamailio validates digest locally using returned HA1            │
 *   │  3. Kamailio updates location table (registrar)                     │
 *   └─────────────────────────────────────────────────────────────────────┘
 *
 *   ┌─────────────────────────────────────────────────────────────────────┐
 *   │ SIP INVITE arrives at Kamailio (inbound call)                       │
 *   │  1. GET  /domain/{domain}/exists  → is this our domain?            │
 *   │  2. POST /authorize/inbound       → full auth + routing resolution │
 *   │     Returns: tenantId, routingTarget, dispatcherSet, AI flags,     │
 *   │             RTPEngine hints, recording flag                         │
 *   │  3. GET  /dispatcher/{setId}      → FreeSWITCH destination(s)     │
 *   │  4. Kamailio routes INVITE to FreeSWITCH                           │
 *   │  5. POST /events/call-start       → CDR + channel counter          │
 *   └─────────────────────────────────────────────────────────────────────┘
 *
 *   ┌─────────────────────────────────────────────────────────────────────┐
 *   │ Call ends (BYE or timeout)                                          │
 *   │  1. POST /events/call-end         → CDR finalize + counter--       │
 *   └─────────────────────────────────────────────────────────────────────┘
 *
 *   ┌─────────────────────────────────────────────────────────────────────┐
 *   │ AI service escalation (voice-brain triggers transfer)               │
 *   │  1. POST /escalation              → ESL transfer/hangup            │
 *   └─────────────────────────────────────────────────────────────────────┘
 *
 * ALL responses MUST be <3 seconds (Kamailio http_client timeout).
 */
@Slf4j
@RestController
@RequestMapping("/internal/kamailio")
@RequiredArgsConstructor
public class KamailioController {

    private final SubscriberRepository subscriberRepository;
    private final SipDomainRepository domainRepository;
    private final DispatcherRepository dispatcherRepository;
    private final CallAuthorizationService authService;
    private final CdrService cdrService;
    private final ChannelCounterService channelCounter;
    private final ActiveCallTracker callTracker;
    private final RtpEngineConfigRedisService rtpEngineRedis;
    private final CallControlService callControlService;

    // ═══════════════════════════════════════════════════════════
    // 1. DIGEST AUTH — Kamailio gets HA1 to validate SIP credentials
    // ═══════════════════════════════════════════════════════════

    /**
     * SIP digest authentication — HA1 lookup.
     *
     * Kamailio's auth module calls this when it receives a REGISTER or INVITE
     * with Authorization header. Kamailio extracts username + domain from
     * the SIP Authorization header and sends them here.
     *
     * PBX-Core returns the HA1 hash. Kamailio does the actual digest
     * validation (nonce comparison, replay protection) locally.
     *
     * Request:  {"username": "1001", "domain": "tenant-acme.dalaillama.in"}
     * Response: {"ha1": "a1b2c3...", "ha1b": "d4e5f6...", "tenant_id": "...",
     *            "subscriber_type": "AGENT", "display_name": "John", "is_active": true}
     *
     * If subscriber not found or inactive → 404 → Kamailio sends 403 to client.
     */
    @PostMapping("/auth/digest")
    public ResponseEntity<Map<String, Object>> digestAuth(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String domain = body.get("domain");

        if (username == null || domain == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "username and domain required"));
        }

        Optional<Subscriber> subOpt = subscriberRepository.findByUsernameAndDomain(username, domain);

        if (subOpt.isEmpty()) {
            log.debug("Digest auth: {}@{} not found", username, domain);
            return ResponseEntity.status(404).body(Map.of("error", "subscriber_not_found"));
        }

        Subscriber sub = subOpt.get();

        if (Boolean.FALSE.equals(sub.getIsActive())) {
            log.debug("Digest auth: {}@{} is inactive", username, domain);
            return ResponseEntity.status(403).body(Map.of("error", "subscriber_inactive"));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ha1", sub.getHa1());
        result.put("ha1b", sub.getHa1b());
        result.put("tenant_id", sub.getTenantId().toString());
        result.put("subscription_id", sub.getSubscriptionId().toString());
        result.put("subscriber_type", sub.getSubscriberType().name());
        result.put("display_name", sub.getDisplayName());
        result.put("is_active", true);

        log.debug("Digest auth OK: {}@{} (tenant={})", username, domain, sub.getTenantId());
        return ResponseEntity.ok(result);
    }

    // ═══════════════════════════════════════════════════════════
    // 2. DOMAIN CHECK — is this Request-URI domain ours?
    // ═══════════════════════════════════════════════════════════

    /**
     * Kamailio checks if the Request-URI domain belongs to our platform.
     * Called early in request_route before any heavy processing.
     *
     * Returns 200 with domain info if exists, 404 if not.
     * Kamailio uses 404 to decide: relay externally or reject.
     */
    @GetMapping("/domain/{domain}/exists")
    public ResponseEntity<Map<String, Object>> domainExists(@PathVariable String domain) {
        return domainRepository.findByDomain(domain)
                .filter(d -> Boolean.TRUE.equals(d.getIsActive()))
                .map(d -> {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("exists", true);
                    result.put("domain", d.getDomain());
                    result.put("tenant_id", d.getTenantId().toString());
                    result.put("did", d.getDid());
                    return ResponseEntity.ok(result);
                })
                .orElse(ResponseEntity.status(404).body(Map.of("exists", false)));
    }

    // ═══════════════════════════════════════════════════════════
    // 3. DISPATCHER — get FreeSWITCH destinations for routing
    // ═══════════════════════════════════════════════════════════

    /**
     * Returns FreeSWITCH destinations for a dispatcher set.
     * Kamailio uses this instead of ds_select_dst (which needs DB).
     *
     * Response: list of destinations with priority + flags.
     * Kamailio picks the highest-priority available destination.
     */
    @GetMapping("/dispatcher/{setId}")
    public ResponseEntity<Map<String, Object>> getDispatchers(@PathVariable Integer setId) {
        List<Dispatcher> dispatchers = dispatcherRepository.findBySetid(setId);

        if (dispatchers.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "no_dispatchers", "set_id", setId));
        }

        List<Map<String, Object>> destinations = dispatchers.stream()
                .map(d -> {
                    Map<String, Object> dest = new LinkedHashMap<>();
                    dest.put("destination", d.getDestination());
                    dest.put("priority", d.getPriority());
                    dest.put("flags", d.getFlags());
                    dest.put("attrs", d.getAttrs());
                    dest.put("description", d.getDescription());
                    return dest;
                })
                .toList();

        return ResponseEntity.ok(Map.of(
                "set_id", setId,
                "count", destinations.size(),
                "destinations", destinations
        ));
    }

    // ═══════════════════════════════════════════════════════════
    // 4. CALL AUTHORIZATION — full auth + routing for INVITE
    // ═══════════════════════════════════════════════════════════

    /**
     * Inbound call authorization — the BIG one.
     *
     * Kamailio calls this after domain check passes, before routing to FreeSWITCH.
     * PBX-Core does: tenant resolve → subscription check → channel limit →
     *                routing policy → product-based default routing.
     *
     * Response includes EVERYTHING Kamailio needs to route the call:
     *   - tenantId, subscriptionId (for SIP headers → FreeSWITCH)
     *   - routingTarget, dispatcherSet (where to send the INVITE)
     *   - productCode, aiEnabled, recordingEnabled (for SIP headers → FreeSWITCH dialplan)
     *   - rtpengine hints (codecs, recording, AI fork)
     */
    @PostMapping("/authorize/inbound")
    public ResponseEntity<Map<String, Object>> authorizeInbound(@RequestBody Map<String, String> body) {
        Map<String, Object> result = authService.authorizeInbound(
                body.get("didNumber"),
                body.get("callerNumber"),
                body.get("callId"),
                body.get("domain")
        );

        boolean allowed = Boolean.TRUE.equals(result.get("allowed"));

        // If allowed, enrich with RTPEngine hints from Redis
        if (allowed && result.get("tenantId") != null) {
            UUID tenantId = UUID.fromString(result.get("tenantId").toString());
            rtpEngineRedis.get(tenantId).ifPresent(rtpConfig -> {
                result.put("rtpengine_codecs", rtpConfig.get("codecs"));
                result.put("rtpengine_recording", rtpConfig.get("recording_enabled"));
                result.put("rtpengine_ai_fork", rtpConfig.get("ai_fork_enabled"));
                result.put("rtpengine_ai_fork_target", rtpConfig.get("ai_fork_target"));
            });
        }

        return allowed ? ResponseEntity.ok(result) : ResponseEntity.status(403).body(result);
    }

    /**
     * Outbound call authorization.
     */
    @PostMapping("/authorize/outbound")
    public ResponseEntity<Map<String, Object>> authorizeOutbound(@RequestBody Map<String, String> body) {
        Map<String, Object> result = authService.authorizeOutbound(
                body.get("callerNumber"),
                body.get("destination"),
                UUID.fromString(body.get("tenantId"))
        );

        boolean allowed = Boolean.TRUE.equals(result.get("allowed"));
        return allowed ? ResponseEntity.ok(result) : ResponseEntity.status(403).body(result);
    }

    // ═══════════════════════════════════════════════════════════
    // 5. CALL LIFECYCLE EVENTS
    // ═══════════════════════════════════════════════════════════

    /**
     * Call started — INVITE forwarded to FreeSWITCH.
     * Creates CDR + increments channel counter + tracks in Redis.
     */
    @PostMapping("/events/call-start")
    public ResponseEntity<Void> callStart(@RequestBody Map<String, String> body) {
        UUID tenantId = UUID.fromString(body.get("tenantId"));
        String callId = body.get("callId");
        String direction = body.getOrDefault("direction", "INBOUND");
        UUID subscriptionId = body.get("subscriptionId") != null
                ? UUID.fromString(body.get("subscriptionId")) : tenantId;

        // Channel counter
        if ("INBOUND".equalsIgnoreCase(direction)) {
            channelCounter.incrementInbound(tenantId);
        } else {
            channelCounter.incrementOutbound(tenantId);
        }

        // CDR
        cdrService.createCdr(
                tenantId, subscriptionId, callId,
                CallDirection.valueOf(direction.toUpperCase()),
                body.get("callerNumber"),
                body.get("calleeNumber"),
                body.get("didNumber"),
                body.get("productCode")
        );

        // Active call tracker (Redis)
        callTracker.trackCall(callId, tenantId, Map.of(
                "direction", direction,
                "caller_number", body.getOrDefault("callerNumber", ""),
                "callee_number", body.getOrDefault("calleeNumber", ""),
                "did_number", body.getOrDefault("didNumber", ""),
                "product_code", body.getOrDefault("productCode", ""),
                "subscription_id", subscriptionId.toString(),
                "status", "RINGING"
        ));

        log.info("Call started: callId={} tenant={} {}", callId, tenantId, direction);
        return ResponseEntity.ok().build();
    }

    /**
     * Call ended — BYE received or timeout.
     * Updates CDR + decrements channel counter + removes from Redis.
     */
    @PostMapping("/events/call-end")
    public ResponseEntity<Void> callEnd(@RequestBody Map<String, String> body) {
        UUID tenantId = UUID.fromString(body.get("tenantId"));
        String callId = body.get("callId");
        String direction = body.getOrDefault("direction", "INBOUND");

        // Channel counter
        if ("INBOUND".equalsIgnoreCase(direction)) {
            channelCounter.decrementInbound(tenantId);
        } else {
            channelCounter.decrementOutbound(tenantId);
        }

        // CDR
        cdrService.markEnded(callId, body.get("hangupCause"));

        // Active call tracker
        callTracker.removeCall(callId, tenantId);

        log.info("Call ended: callId={} tenant={} cause={}", callId, tenantId, body.get("hangupCause"));
        return ResponseEntity.ok().build();
    }

    // ═══════════════════════════════════════════════════════════
    // 6. AI ESCALATION — voice-brain triggers call transfer/hangup
    // ═══════════════════════════════════════════════════════════

    /**
     * AI service (voice-brain) calls this to escalate a call.
     *
     * Escalation types:
     *   TRANSFER_QUEUE   → transfer call to a queue (ESL uuid_transfer)
     *   TRANSFER_AGENT   → transfer to specific agent extension
     *   TRANSFER_EXTERNAL → transfer to external number
     *   HANGUP           → end the call (bot said goodbye)
     *
     * voice-brain sends:
     *   {
     *     "call_id": "abc-123",
     *     "tenant_id": "...",
     *     "escalation_type": "TRANSFER_QUEUE",
     *     "target": "sales_queue",
     *     "reason": "caller requested agent",
     *     "transcript_summary": "Caller asked about pricing...",
     *     "sentiment_score": -0.3,
     *     "intent": "billing_inquiry",
     *     "context": {"custom_field": "value"}
     *   }
     */
    @PostMapping("/escalation")
    public ResponseEntity<Map<String, Object>> escalate(@RequestBody Map<String, Object> body) {
        String callId = (String) body.get("call_id");
        String tenantIdStr = (String) body.get("tenant_id");
        String escalationType = (String) body.get("escalation_type");
        String target = (String) body.get("target");
        String reason = (String) body.get("reason");

        if (callId == null || escalationType == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "call_id and escalation_type required"));
        }

        UUID tenantId = tenantIdStr != null ? UUID.fromString(tenantIdStr) : null;

        log.info("AI escalation: callId={} type={} target={} reason={}", callId, escalationType, target, reason);

        // Update CDR with AI context
        String transcript = (String) body.get("transcript_summary");
        if (transcript != null) {
            cdrService.setTranscript(callId, transcript, null);
        }
        Object sentiment = body.get("sentiment_score");
        if (sentiment instanceof Number n) {
            cdrService.setTranscript(callId, null, java.math.BigDecimal.valueOf(n.doubleValue()));
        }

        // Update active call tracker
        callTracker.updateField(callId, "escalation_type", escalationType);
        callTracker.updateField(callId, "escalation_reason", reason != null ? reason : "");

        // Execute the escalation via ESL
        String result;
        try {
            result = switch (escalationType.toUpperCase()) {
                case "TRANSFER_QUEUE" -> {
                    // Transfer to queue — FreeSWITCH dialplan handles queue routing
                    String context = tenantId != null ? "tenant_" + tenantId : "default";
                    yield callControlService.transfer(callId, target, context);
                }
                case "TRANSFER_AGENT" -> {
                    // Transfer to agent extension
                    String context = tenantId != null ? "tenant_" + tenantId : "default";
                    yield callControlService.transfer(callId, target, context);
                }
                case "TRANSFER_EXTERNAL" -> {
                    // Transfer to external number via trunk
                    yield callControlService.transfer(callId, target, "default");
                }
                case "HANGUP" -> {
                    yield callControlService.hangup(callId, "NORMAL_CLEARING");
                }
                default -> {
                    log.warn("Unknown escalation type: {}", escalationType);
                    yield "UNKNOWN_TYPE";
                }
            };
        } catch (Exception e) {
            log.error("Escalation failed for callId={}: {}", callId, e.getMessage());
            return ResponseEntity.status(500).body(Map.of(
                    "error", "escalation_failed",
                    "message", e.getMessage()
            ));
        }

        // Publish escalation event to WebSocket
        if (tenantId != null) {
            callTracker.updateStatus(callId, "TRANSFERRED");
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("call_id", callId);
        response.put("escalation_type", escalationType);
        response.put("target", target);
        response.put("result", result);
        response.put("status", "executed");

        log.info("AI escalation executed: callId={} type={} target={}", callId, escalationType, target);
        return ResponseEntity.ok(response);
    }
}