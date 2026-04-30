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



/**
 * Kamailio integration controller — the ONLY interface between Kamailio and telecom data.
 *
 * Kamailio has NO direct DB access. EVERY lookup comes through PBX-Core HTTP.
 *
 * Endpoints (in call flow order):
 *   POST /auth/digest              → HA1 for SIP digest validation
 *   GET  /domain/{domain}/exists   → domain ownership check
 *   POST /authorize/inbound        → full auth + routing + botId
 *   POST /authorize/outbound       → DNC + balance + channel limit
 *   GET  /dispatcher/{setId}       → FreeSWITCH destinations
 *   POST /events/call-start        → CDR create + channel counter++
 *   POST /events/call-end          → CDR finalize + channel counter--
 *
 * Note: AI escalation is at /internal/ai/escalation (AiController), NOT here.
 *       voice-brain calls AiController directly for transfers/hangups.
 *
 * ALL responses MUST be <3 seconds (Kamailio http_client timeout).
 */
@Slf4j
@RestController
@RequestMapping("/kamailio")
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

    // ═══════════════════════════════════════════════════════════
    // 1. DIGEST AUTH — Kamailio gets HA1 to validate SIP credentials
    // ═══════════════════════════════════════════════════════════

    /**
     * Request:  {"username": "1001", "domain": "tenant-acme.dalaillama.in"}
     * Response: {"ha1": "...", "ha1b": "...", "tenant_id": "...",
     *            "subscriber_type": "AGENT", "display_name": "...", "is_active": true}
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
    // 2. DOMAIN CHECK
    // ═══════════════════════════════════════════════════════════

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
    // 3. DISPATCHER — FreeSWITCH destinations
    // ═══════════════════════════════════════════════════════════

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
    // 4. CALL AUTHORIZATION
    // ═══════════════════════════════════════════════════════════

    /**
     * Inbound — resolves tenant, checks subscription/channels, returns routing + botId.
     * Enriches with RTPEngine hints from Redis.
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

        if (allowed && result.get("tenantId") != null) {
            UUID tenantId = UUID.fromString(result.get("tenantId").toString());
            rtpEngineRedis.get(tenantId).ifPresent(rtpConfig -> {
                result.put("rtpengine_codecs", rtpConfig.get("codecs"));
                result.put("rtpengine_recording", rtpConfig.get("recording_enabled"));
            });
        }

        return allowed ? ResponseEntity.ok(result) : ResponseEntity.status(403).body(result);
    }

    /**
     * Outbound — DNC check, balance check, channel limit.
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
     * Call started — creates CDR, increments channel counter, tracks in Redis.
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

        // Active call tracker (Redis) — stores direction for call-end to read
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
     * Call ended — finalizes CDR, decrements channel counter, removes from Redis.
     *
     * CRITICAL FIX: Kamailio BYE handler does NOT reliably send "direction" because
     * $avp() variables don't persist across SIP transactions (INVITE and BYE are
     * separate transactions). So we read direction from ActiveCallTracker in Redis
     * (stored during call-start) BEFORE removing the tracker entry.
     *
     * Without this fix, all outbound call endings would decrement the INBOUND counter
     * (because the default was "INBOUND"), causing counter drift over time.
     */
    @PostMapping("/events/call-end")
    public ResponseEntity<Void> callEnd(@RequestBody Map<String, String> body) {
        UUID tenantId = UUID.fromString(body.get("tenantId"));
        String callId = body.get("callId");

        // Direction: prefer Kamailio body if present, fallback to Redis (source of truth)
        String direction = body.get("direction");
        if (direction == null || direction.isBlank()) {
            Optional<Map<Object, Object>> tracked = callTracker.getCallDetail(callId);
            direction = tracked.map(m -> String.valueOf(m.getOrDefault("direction", "INBOUND")))
                    .orElse("INBOUND");
        }

        // Channel counter — now using correct direction from Redis
        if ("INBOUND".equalsIgnoreCase(direction)) {
            channelCounter.decrementInbound(tenantId);
        } else {
            channelCounter.decrementOutbound(tenantId);
        }

        // CDR — finalize with hangup cause, calculate billing cost, send Kafka event
        cdrService.markEnded(callId, body.get("hangupCause"));

        // Active call tracker — remove AFTER reading direction
        callTracker.removeCall(callId, tenantId);

        log.info("Call ended: callId={} tenant={} dir={} cause={}",
                callId, tenantId, direction, body.get("hangupCause"));
        return ResponseEntity.ok().build();
    }
}