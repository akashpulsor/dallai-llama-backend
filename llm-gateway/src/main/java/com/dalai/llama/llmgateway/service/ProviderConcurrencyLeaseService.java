package com.dalai.llama.llmgateway.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Caps how many calls to a given provider account may be simultaneously in-flight (blocked)
 * at once -- the gate {@link RateLimiterService}'s token bucket does NOT provide. The RPM/TPM
 * bucket only throttles how fast NEW calls are admitted; for a long-blocking dispatch chain
 * (fal.ai's submit-then-poll loop can run minutes), several calls can be admitted seconds apart
 * and still all be blocked on the provider at the same moment, with nothing capping that true
 * concurrency. This does.
 *
 * <p>Implementation: a Redis sorted set per provider, one member per in-flight lease (the job_id),
 * scored by its own expiry time (now + the model's timeout, plus a safety margin). Acquire prunes
 * every member whose score has already passed before counting and admitting -- so a lease from a
 * pod that crashed mid-call self-expires and is cleaned up by the very next acquire call, with no
 * separate reconciliation job needed (unlike pbx-core's {@code ChannelCounterService}, a plain
 * INCR/DECR counter that has no TTL and relies on explicit release events / periodic drift
 * reconciliation to stay correct).
 */
@Service
public class ProviderConcurrencyLeaseService {

    private static final String ACQUIRE_SCRIPT = """
            local key = KEYS[1]
            local now = tonumber(ARGV[1])
            local member = ARGV[2]
            local expiry = tonumber(ARGV[3])
            local maxConcurrent = tonumber(ARGV[4])

            redis.call('ZREMRANGEBYSCORE', key, '-inf', now)
            local current = redis.call('ZCARD', key)
            if current < maxConcurrent then
              redis.call('ZADD', key, expiry, member)
              redis.call('EXPIRE', key, 3600)
              return 1
            end
            return 0
            """;

    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<Long> acquireScript;

    public ProviderConcurrencyLeaseService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.acquireScript = new DefaultRedisScript<>(ACQUIRE_SCRIPT, Long.class);
    }

    /**
     * @param leaseTtlSeconds how long this lease self-expires after, if never released -- must
     *                        cover the real call duration (model timeout + margin), or a
     *                        genuinely still-running call would get evicted and double-counted.
     * @return true if the lease was granted, false if the provider is already at maxConcurrent.
     */
    public boolean tryAcquire(String providerId, UUID jobId, int maxConcurrent, long leaseTtlSeconds) {
        double now = System.currentTimeMillis() / 1000.0;
        double expiry = now + leaseTtlSeconds;
        Long allowed = redisTemplate.execute(
                acquireScript,
                List.of(key(providerId)),
                String.valueOf(now),
                jobId.toString(),
                String.valueOf(expiry),
                String.valueOf(maxConcurrent)
        );
        return allowed != null && allowed == 1L;
    }

    /** Always call from a finally block around the provider call -- releasing early is safe
     * (the lease is gone either way once its TTL passes), never releasing just means the next
     * acquire waits out the TTL instead of seeing it freed immediately. */
    public void release(String providerId, UUID jobId) {
        redisTemplate.opsForZSet().remove(key(providerId), jobId.toString());
    }

    /** Current in-flight count for a provider -- observability only, not used for admission
     * (acquire's own atomic prune-then-check is the source of truth). */
    public long currentLeases(String providerId) {
        double now = System.currentTimeMillis() / 1000.0;
        redisTemplate.opsForZSet().removeRangeByScore(key(providerId), Double.NEGATIVE_INFINITY, now);
        Long count = redisTemplate.opsForZSet().zCard(key(providerId));
        return count != null ? count : 0;
    }

    private String key(String providerId) {
        return "concurrency:" + providerId;
    }
}
