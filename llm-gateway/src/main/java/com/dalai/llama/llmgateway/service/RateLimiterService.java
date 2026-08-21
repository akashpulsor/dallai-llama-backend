package com.dalai.llama.llmgateway.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Redis-backed token bucket, doc §4: capacity = burst allowance (= RPM), refill rate = RPM/60
 * tokens per second, atomic check-and-decrement via a single Lua script so every gateway pod
 * shares one accurate counter. Doc §4's two-level quota: a per-model bucket (protects the
 * provider's global limit) and a per-tenant bucket (protects tenants from each other) --
 * both must pass before dispatch.
 *
 * <p>Known v1 simplification: if the model-level bucket passes but the tenant-level bucket then
 * rejects, the model-level token already spent is not refunded. Acceptable at this traffic
 * scale; a compensating decrement is a follow-up if it ever matters.
 */
@Service
public class RateLimiterService {

    private static final String SCRIPT = """
            local key = KEYS[1]
            local capacity = tonumber(ARGV[1])
            local refill_rate = tonumber(ARGV[2])
            local now = tonumber(ARGV[3])
            local requested = tonumber(ARGV[4])

            local bucket = redis.call('HMGET', key, 'tokens', 'ts')
            local tokens = tonumber(bucket[1])
            local ts = tonumber(bucket[2])

            if tokens == nil then
              tokens = capacity
              ts = now
            end

            local elapsed = now - ts
            if elapsed < 0 then
              elapsed = 0
            end
            tokens = math.min(capacity, tokens + elapsed * refill_rate)

            local allowed = 0
            if tokens >= requested then
              tokens = tokens - requested
              allowed = 1
            end

            redis.call('HMSET', key, 'tokens', tostring(tokens), 'ts', tostring(now))
            redis.call('EXPIRE', key, 3600)

            return allowed
            """;

    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<Long> script;
    private final int defaultTenantRpm;

    public RateLimiterService(
            StringRedisTemplate redisTemplate,
            @Value("${llm-gateway.default-tenant-rpm}") int defaultTenantRpm
    ) {
        this.redisTemplate = redisTemplate;
        this.defaultTenantRpm = defaultTenantRpm;
        this.script = new DefaultRedisScript<>(SCRIPT, Long.class);
    }

    public int defaultTenantRpm() {
        return defaultTenantRpm;
    }

    /** @throws GatewayException 429 if either the model or the tenant bucket is exhausted. */
    public void checkAndConsume(String modelId, int modelRpm, String tenantId, int tenantRpm) {
        if (!tryConsume("ratelimit:model:" + modelId, modelRpm)) {
            throw GatewayException.rateLimited("Rate limit exceeded for model_id=" + modelId);
        }
        if (!tryConsume("ratelimit:tenant:" + tenantId, tenantRpm)) {
            throw GatewayException.rateLimited("Rate limit exceeded for tenant_id=" + tenantId);
        }
    }

    boolean tryConsume(String bucketKey, int capacityRpm) {
        double refillRatePerSecond = capacityRpm / 60.0;
        double now = System.currentTimeMillis() / 1000.0;
        Long allowed = redisTemplate.execute(
                script,
                List.of(bucketKey),
                String.valueOf(capacityRpm),
                String.valueOf(refillRatePerSecond),
                String.valueOf(now),
                "1"
        );
        return allowed != null && allowed == 1L;
    }
}
