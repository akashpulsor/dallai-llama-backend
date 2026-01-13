package com.dalai.llama.agent.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Periodic scan to clear possibly stale `call:assigned` keys that are expired by other means,
 * or to log long-lived assignments. This is optional but useful in testing.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AssignmentCleaner {

    private final RedisTemplate<String, String> redis;

    // run every minute
    @Scheduled(fixedDelay = 60000)
    public void clean() {
        try {
            Set<String> keys = redis.keys("call:assigned:*");
            if (keys == null || keys.isEmpty()) return;
            for (String k : keys) {
                String val = redis.opsForValue().get(k);
                if (!"true".equalsIgnoreCase(val)) {
                    redis.delete(k);
                }
            }
        } catch (Exception e) {
            log.warn("AssignmentCleaner error: {}", e.getMessage());
        }
    }
}