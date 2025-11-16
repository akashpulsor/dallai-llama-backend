package com.dalai.llama.agent.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class AgentEventsProducer {
    
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;
    
    @Value("${app.kafka.topics.agent-events}")
    private String agentEventsTopic;

    public void publishAgentEvent(String tenantId, String eventType, Object payload) {
        try {
            Map<String, Object> event = new HashMap<>();
            event.put("eventType", eventType);
            event.put("tenantId", tenantId);
            event.put("timestamp", System.currentTimeMillis());
            event.put("payload", payload);
            
            kafkaTemplate.send(agentEventsTopic, tenantId, event);
            log.debug("Published agent event: {} to topic: {}", eventType, agentEventsTopic);
        } catch (Exception e) {
            log.error("Failed to publish agent event: {}", eventType, e);
        }
    }

    public void publishCallEvent(String tenantId, String eventType, Object payload) {
        publishEvent(tenantId, eventType, payload);
    }

    public void publishEvent(String tenantId, String eventType, Object payload) {
        publishAgentEvent(tenantId, eventType, payload);
    }
}
