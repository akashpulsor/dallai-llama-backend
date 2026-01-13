package com.dalai.llama.tenant.util;


import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@RequiredArgsConstructor
public class DistributedLock {

    private final StringRedisTemplate redisTemplate;

    public boolean acquire(String key, String value, Duration ttl) {
        Boolean success = redisTemplate.opsForValue()
                .setIfAbsent(key, value, ttl);
        return Boolean.TRUE.equals(success);
    }

    public void release(String key, String value) {
        String current = redisTemplate.opsForValue().get(key);
        if (value.equals(current)) {
            redisTemplate.delete(key);
        }
    }
}
