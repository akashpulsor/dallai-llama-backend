package com.dalai.llama.agent.service;


import com.dalai.llama.agent.entity.Agent;
import com.dalai.llama.agent.repository.AgentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
public class PresenceStore {

    private final RedisTemplate<String, String> redis;
    private final AgentRepository agentRepository; // used for lookup username -> agentId

    @Value("${app.presence.ttl-minutes:60}")
    private long presenceTtlMinutes;

    private String key(String tenantId, Long agentId) {
        return "agent:presence:" + tenantId + ":" + agentId;
    }

    // Set desiredOnline/desiredAvailability from UI
    public void setDesiredOnline(String tenantId, Long agentId, boolean desiredOnline) {
        String k = key(tenantId, agentId);
        redis.opsForHash().put(k, "desiredOnline", String.valueOf(desiredOnline));
        // update lastSeen for activity
        redis.opsForHash().put(k, "lastSeen", OffsetDateTime.now().toString());
        // ensure TTL
        redis.expire(k, Duration.ofMinutes(presenceTtlMinutes));
        // recompute effective fields
        mergePresence(tenantId, agentId);
    }

    public void setDesiredAvailability(String tenantId, Long agentId, String desiredAvailability) {
        String k = key(tenantId, agentId);
        redis.opsForHash().put(k, "desiredAvailability", desiredAvailability == null ? "" : desiredAvailability);
        redis.opsForHash().put(k, "lastSeen", OffsetDateTime.now().toString());
        redis.expire(k, Duration.ofMinutes(presenceTtlMinutes));
        mergePresence(tenantId, agentId);
    }

    // Called when controller / AgentService sets agent as "online" initially (bootstrap)
    public void setOnline(String tenantId,
                          Long agentId,
                          String username,
                          String realm,
                          String sipUrl,
                          String desiredAvailability) {

        String k = key(tenantId, agentId);

        Map<String, String> map = new HashMap<>();
        map.put("username", username == null ? "" : username);
        map.put("realm", realm == null ? "" : realm);
        map.put("sipUrl", sipUrl == null ? "" : sipUrl);
        map.put("desiredOnline", "true");
        map.put("desiredAvailability", desiredAvailability == null ? "AVAILABLE" : desiredAvailability);
        map.put("lastSeen", OffsetDateTime.now().toString());

        redis.opsForHash().putAll(k, map);
        redis.expire(k, Duration.ofMinutes(presenceTtlMinutes));

        // recompute effective fields
        mergePresence(tenantId, agentId);
    }

    public void setOffline(String tenantId, Long agentId) {
        String k = key(tenantId, agentId);
        // Mark desiredOnline false, sipRegistered false, effective computed by mergePresence
        Map<String, String> map = new HashMap<>();
        map.put("desiredOnline", "false");
        map.put("sipRegistered", "false");
        map.put("desiredAvailability", "UNAVAILABLE");
        map.put("lastSeen", OffsetDateTime.now().toString());

        redis.opsForHash().putAll(k, map);
        // set short TTL to clear offline entries
        redis.expire(k, Duration.ofMinutes(5));
        mergePresence(tenantId, agentId);
    }

    /**
     * Called by Kafka listener when Kamailio sends REGISTER events.
     * username here is aor (sip username). We try to resolve agentId from username+tenantId.
     */
    public void updateContact(String tenantId, String username, String contact, int expiresSeconds) {
        Long agentId = lookupAgentId(tenantId, username);
        if (agentId == null) {
            // no local agent found; ignore or log
            return;
        }
        String k = key(tenantId, agentId);
        Map<String, String> map = new HashMap<>();
        map.put("sipRegistered", "true");
        map.put("contact", contact == null ? "" : contact);
        map.put("expires", String.valueOf(expiresSeconds));
        map.put("lastSeen", OffsetDateTime.now().toString());
        redis.opsForHash().putAll(k, map);

        // set TTL based on expires (plus margin)
        redis.expire(k, Duration.ofSeconds(expiresSeconds + 30L));
        mergePresence(tenantId, agentId);
    }

    /**
     * Called by Kafka listener on unregister/expiry
     */
    public void clearContact(String tenantId, String username) {
        Long agentId = lookupAgentId(tenantId, username);
        if (agentId == null) return;
        String k = key(tenantId, agentId);
        redis.opsForHash().put(k, "sipRegistered", "false");
        redis.opsForHash().put(k, "contact", "");
        redis.opsForHash().put(k, "expires", "0");
        redis.opsForHash().put(k, "lastSeen", OffsetDateTime.now().toString());
        // recompute effective
        mergePresence(tenantId, agentId);
    }

    public void updateLastSeen(String tenantId, Long agentId) {
        String k = key(tenantId, agentId);
        redis.opsForHash().put(k, "lastSeen", OffsetDateTime.now().toString());
        redis.expire(k, Duration.ofMinutes(presenceTtlMinutes));
    }

    public void updateAvailability(String tenantId, Long agentId, Agent.AvailabilityStatus status) {
        String k = key(tenantId, agentId);
        redis.opsForHash().put(k, "desiredAvailability", status == null ? "" : status.name());
        redis.opsForHash().put(k, "lastSeen", OffsetDateTime.now().toString());
        redis.expire(k, Duration.ofMinutes(presenceTtlMinutes));
        mergePresence(tenantId, agentId);
    }

    /**
     * Merge desired + real (sipRegistered) into effective fields used for routing.
     * effectiveOnline = desiredOnline && sipRegistered
     * effectiveAvailability = if not effectiveOnline -> UNAVAILABLE else desiredAvailability
     */
    public void mergePresence(String tenantId, Long agentId) {
        String k = key(tenantId, agentId);
        Map<Object, Object> raw = redis.opsForHash().entries(k);
        boolean desiredOnline = "true".equalsIgnoreCase(asString(raw.get("desiredOnline")));
        boolean sipRegistered = "true".equalsIgnoreCase(asString(raw.get("sipRegistered")));
        String desiredAvailability = asString(raw.get("desiredAvailability"));

        boolean effectiveOnline = desiredOnline && sipRegistered;
        String effectiveAvailability;
        if (!effectiveOnline) {
            effectiveAvailability = "UNAVAILABLE";
        } else {
            effectiveAvailability = (desiredAvailability == null || desiredAvailability.isBlank())
                    ? "AVAILABLE"
                    : desiredAvailability;
        }

        // write computed fields atomically
        Map<String, String> out = new HashMap<>();
        out.put("effectiveOnline", String.valueOf(effectiveOnline));
        out.put("effectiveAvailability", effectiveAvailability);
        redis.opsForHash().putAll(k, out);
        // refresh TTL
        redis.expire(k, Duration.ofMinutes(presenceTtlMinutes));
    }

    public boolean isEffectivelyOnline(String tenantId, Long agentId) {
        String k = key(tenantId, agentId);
        String v = (String) redis.opsForHash().get(k, "effectiveOnline");
        return "true".equalsIgnoreCase(v);
    }

    public Map<String, String> getPresence(String tenantId, Long agentId) {
        String k = key(tenantId, agentId);
        Map<Object, Object> raw = redis.opsForHash().entries(k);
        Map<String, String> out = new HashMap<>();
        for (Entry<Object, Object> e : raw.entrySet()) {
            out.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
        }
        return out;
    }

    public void deletePresence(String tenantId, Long agentId) {
        redis.delete(key(tenantId, agentId));
    }

    // helper
    private String asString(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    // Resolve agentId by username + tenantId (expects repository method)
    private Long lookupAgentId(String tenantId, String username) {
        return agentRepository.findByTenantIdAndUsername(tenantId, username)
                .map(a -> a.getId())
                .orElse(null);
    }

    public void setCallState(String tenantId, Long agentId, String state) {
        redis.opsForHash().put(key(tenantId, agentId), "callState", state);
    }

    public void clearCallState(String tenantId, Long agentId) {
        redis.opsForHash().put(key(tenantId, agentId), "callState", "");
    }

    public void setCurrentCall(String tenantId, Long agentId, String callId) {
        redis.opsForHash().put(key(tenantId, agentId), "currentCallId", callId);
    }

    public void setAcwUntil(String tenantId, Long agentId, Instant until) {
        redis.opsForHash().put(key(tenantId, agentId), "acwUntil", until.toString());
    }

    public void clearAcw(String tenantId, Long agentId) {
        redis.opsForHash().put(key(tenantId, agentId), "acwUntil", "");
    }

    public Long lookupAgentIdInternal(String tenantId, String username) {
        return agentRepository
                .findByTenantIdAndUsername(tenantId, username)
                .map(a -> a.getId())
                .orElse(null);
    }

}

