package com.dalai.llama.pbx.core.redis;



import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Tracks live calls in Redis for real-time dashboard and call control.
 *
 * Two key patterns:
 *
 * 1. calls:active:{tenantId}  → SET of callIds
 *    Used by: SupervisorController dashboard (how many live calls?)
 *             GET /api/v1/calls/active (list all active calls for tenant)
 *             ChannelCounterService reconciliation
 *
 * 2. calls:detail:{callId}    → HASH {
 *      tenant_id, subscription_id, direction, caller_number, callee_number,
 *      did_number, agent_id, queue_id, product_code, status, start_time
 *    }
 *    Used by: CallController (get call info for transfer/hold/hangup)
 *             SupervisorController (call details for listen/whisper/barge)
 *             WebSocketEventPublisher (enrich events with call context)
 *
 * TTL: 4 hours on detail HASH (safety net — call-end should clean up).
 *      No TTL on active SET (members are removed individually).
 *
 * Write paths:
 *   - KamailioAuthController /events/call-start → trackCall
 *   - EslEventListener CHANNEL_ANSWER          → updateStatus + setAgentId
 *   - EslEventListener CHANNEL_HANGUP          → removeCall
 *   - KamailioAuthController /events/call-end  → removeCall (backup)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ActiveCallTracker {

    private final StringRedisTemplate redis;

    private static final String ACTIVE_PREFIX = "calls:active:";
    private static final String DETAIL_PREFIX = "calls:detail:";
    private static final Duration DETAIL_TTL = Duration.ofHours(4);

    // ═══════════════════════════════════════════════════════════
    // Track a new call (call-start)
    // ═══════════════════════════════════════════════════════════

    public void trackCall(String callId, UUID tenantId, Map<String, String> callDetails) {
        // Add to tenant's active call set
        redis.opsForSet().add(ACTIVE_PREFIX + tenantId, callId);

        // Store call details as HASH
        Map<String, String> details = new HashMap<>(callDetails);
        details.put("tenant_id", tenantId.toString());
        details.put("start_time", Instant.now().toString());
        details.putIfAbsent("status", "RINGING");

        redis.opsForHash().putAll(DETAIL_PREFIX + callId, details);
        redis.expire(DETAIL_PREFIX + callId, DETAIL_TTL);

        log.debug("Tracking call: callId={}, tenant={}", callId, tenantId);
    }

    // ═══════════════════════════════════════════════════════════
    // Update call detail fields (answer, agent assignment, etc.)
    // ═══════════════════════════════════════════════════════════

    public void updateField(String callId, String field, String value) {
        redis.opsForHash().put(DETAIL_PREFIX + callId, field, value);
    }

    public void updateStatus(String callId, String status) {
        updateField(callId, "status", status);
    }

    public void setAgentId(String callId, String agentId) {
        updateField(callId, "agent_id", agentId);
    }

    public void setQueueId(String callId, String queueId) {
        updateField(callId, "queue_id", queueId);
    }

    // ═══════════════════════════════════════════════════════════
    // Remove call (call-end / hangup)
    // ═══════════════════════════════════════════════════════════

    public void removeCall(String callId, UUID tenantId) {
        redis.opsForSet().remove(ACTIVE_PREFIX + tenantId, callId);
        redis.delete(DETAIL_PREFIX + callId);
        log.debug("Removed call: callId={}, tenant={}", callId, tenantId);
    }

    // ═══════════════════════════════════════════════════════════
    // Read — dashboard & call control
    // ═══════════════════════════════════════════════════════════

    /**
     * All active callIds for a tenant — used by GET /api/v1/calls/active.
     */
    public Set<String> getActiveCallIds(UUID tenantId) {
        Set<String> members = redis.opsForSet().members(ACTIVE_PREFIX + tenantId);
        return members != null ? members : Set.of();
    }

    /**
     * Count of active calls — supervisor dashboard metric.
     */
    public long getActiveCallCount(UUID tenantId) {
        Long size = redis.opsForSet().size(ACTIVE_PREFIX + tenantId);
        return size != null ? size : 0;
    }

    /**
     * Full call detail — used by CallController and SupervisorController.
     */
    public Optional<Map<Object, Object>> getCallDetail(String callId) {
        Map<Object, Object> entries = redis.opsForHash().entries(DETAIL_PREFIX + callId);
        return entries.isEmpty() ? Optional.empty() : Optional.of(entries);
    }

    /**
     * All active calls with details — supervisor dashboard.
     * Fetches the SET of callIds then batch-fetches details.
     */
    public List<Map<Object, Object>> getActiveCallsWithDetails(UUID tenantId) {
        Set<String> callIds = getActiveCallIds(tenantId);
        List<Map<Object, Object>> result = new ArrayList<>(callIds.size());
        for (String callId : callIds) {
            Map<Object, Object> detail = redis.opsForHash().entries(DETAIL_PREFIX + callId);
            if (!detail.isEmpty()) {
                detail.put("call_id", callId);
                result.add(detail);
            }
        }
        return result;
    }

    /**
     * Find callId by agent — used by SupervisorController to find
     * which call an agent is on (for listen/whisper/barge).
     */
    public Optional<String> findCallByAgent(UUID tenantId, String agentId) {
        Set<String> callIds = getActiveCallIds(tenantId);
        for (String callId : callIds) {
            Object agent = redis.opsForHash().get(DETAIL_PREFIX + callId, "agent_id");
            if (agentId.equals(agent)) return Optional.of(callId);
        }
        return Optional.empty();
    }

    // ═══════════════════════════════════════════════════════════
    // Reconciliation (ScheduledTasks)
    // ═══════════════════════════════════════════════════════════

    /**
     * Clean stale entries — remove callIds from active set where
     * detail HASH has expired (TTL safety net caught them).
     */
    public int cleanupStaleEntries(UUID tenantId) {
        Set<String> callIds = getActiveCallIds(tenantId);
        int removed = 0;
        for (String callId : callIds) {
            if (Boolean.FALSE.equals(redis.hasKey(DETAIL_PREFIX + callId))) {
                redis.opsForSet().remove(ACTIVE_PREFIX + tenantId, callId);
                removed++;
            }
        }
        if (removed > 0) {
            log.info("Cleaned {} stale call entries for tenant {}", removed, tenantId);
        }
        return removed;
    }
}