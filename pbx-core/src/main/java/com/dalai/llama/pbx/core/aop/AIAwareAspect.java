package com.dalai.llama.pbx.core.aop;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import java.time.Instant;
import java.util.*;

@Aspect
@Component
@RequiredArgsConstructor
@Slf4j
public class AIAwareAspect {

    private final RestTemplate rest = new RestTemplate();

    @Around("@annotation(aiAware)")
    public Object callAIService(ProceedingJoinPoint pjp, AIAware aiAware) throws Throwable {
        Object result = pjp.proceed();
        try {
            Object[] args = pjp.getArgs();
            String tenantId = extract(args, "tenant");
            String callId = extract(args, "call");

            Map<String, Object> payload = Map.of(
                "tenantId", tenantId,
                "callId", callId,
                "feature", aiAware.feature(),
                "timestamp", Instant.now().toString()
            );

            String aiUrl = System.getenv().getOrDefault("AI_SERVICE_URL", "http://ai-service:8000/ai/process");
            rest.postForEntity(aiUrl, payload, Void.class);

            log.info("AI service invoked for feature {} tenant {} call {}", aiAware.feature(), tenantId, callId);
        } catch (Exception e) {
            log.warn("AI service call failed: {}", e.getMessage());
        }
        return result;
    }

    private String extract(Object[] args, String key) {
        for (Object o : args) {
            if (o instanceof String s && s.toLowerCase().contains(key)) return s;
        }
        return UUID.randomUUID().toString();
    }
}
