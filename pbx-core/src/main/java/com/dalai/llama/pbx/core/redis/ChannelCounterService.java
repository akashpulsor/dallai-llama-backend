package com.dalai.llama.pbx.core.redis;


import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Tracks active channel count per tenant in Redis using atomic INCR/DECR.
 *
 * Keys:
 *   channels:{tenantId}:inbound  → current inbound call count
 *   channels:{tenantId}:outbound → current outbound call count
 *   channels:{tenantId}:total    → current total call count
 *
 * Called by:
 *   - CallAuthorizationService → getTotal/getInbound/getOutbound (read before allowing call)
 *   - KamailioAuthController /events/call-start → increment
 *   - KamailioAuthController /events/call-end   → decrement
 *   - EslEventListener CHANNEL_HANGUP           → decrement (backup, in case Kamailio event missed)
 *
 * No TTL on these keys — they are counters that should always be present.
 * ScheduledTasks can reconcile against active call tracker if drift is suspected.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChannelCounterService {

    private final StringRedisTemplate redis;

    private static final String PREFIX = "channels:";

    // ── Increment (call start) ──

    public long incrementInbound(UUID tenantId) {
        long inbound = inc(key(tenantId, "inbound"));
        inc(key(tenantId, "total"));
        log.debug("Channel++ inbound tenant={} → {}", tenantId, inbound);
        return inbound;
    }

    public long incrementOutbound(UUID tenantId) {
        long outbound = inc(key(tenantId, "outbound"));
        inc(key(tenantId, "total"));
        log.debug("Channel++ outbound tenant={} → {}", tenantId, outbound);
        return outbound;
    }

    // ── Decrement (call end) ──

    public void decrementInbound(UUID tenantId) {
        long inbound = dec(key(tenantId, "inbound"));
        dec(key(tenantId, "total"));
        log.debug("Channel-- inbound tenant={} → {}", tenantId, inbound);
    }

    public void decrementOutbound(UUID tenantId) {
        long outbound = dec(key(tenantId, "outbound"));
        dec(key(tenantId, "total"));
        log.debug("Channel-- outbound tenant={} → {}", tenantId, outbound);
    }

    // ── Read (call authorization check) ──

    public long getTotal(UUID tenantId) {
        return getLong(key(tenantId, "total"));
    }

    public long getInbound(UUID tenantId) {
        return getLong(key(tenantId, "inbound"));
    }

    public long getOutbound(UUID tenantId) {
        return getLong(key(tenantId, "outbound"));
    }

    // ── Reconciliation (ScheduledTasks) ──

    /**
     * Force-set the counter to a known value.
     * Used by ScheduledTasks if active call tracker and channel counter drift.
     */
    public void forceSet(UUID tenantId, String direction, long value) {
        redis.opsForValue().set(key(tenantId, direction), String.valueOf(value));
        log.info("Channel counter force-set tenant={} {}={}", tenantId, direction, value);
    }

    /**
     * Reset all counters for a tenant to zero.
     * Used during deprovision or after reconciliation detects negative values.
     */
    public void reset(UUID tenantId) {
        redis.delete(key(tenantId, "inbound"));
        redis.delete(key(tenantId, "outbound"));
        redis.delete(key(tenantId, "total"));
        log.info("Channel counters reset for tenant {}", tenantId);
    }

    // ── Internal ──

    private long inc(String key) {
        Long val = redis.opsForValue().increment(key);
        return val != null ? val : 0;
    }

    private long dec(String key) {
        Long val = redis.opsForValue().decrement(key);
        // Guard against going negative (e.g., duplicate call-end events)
        if (val != null && val < 0) {
            redis.opsForValue().set(key, "0");
            return 0;
        }
        return val != null ? val : 0;
    }

    private long getLong(String key) {
        String val = redis.opsForValue().get(key);
        return val != null ? Long.parseLong(val) : 0;
    }

    private String key(UUID tenantId, String direction) {
        return PREFIX + tenantId + ":" + direction;
    }
}