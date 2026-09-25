package com.dalai.llama.creativeplanning.kafka;

import com.dalai.llama.creativeplanning.service.CreativePlanningException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes {@link ChatJobRequestedEvent} to the {@code llm.job.requested} topic. Same shape as
 * pre-production-service's ChatJobRequestedPublisher but bound to this service's own exception
 * type; a Kafka publish failure IS fatal for the calling flow (no LLM call happens without a
 * successful submit), so we throw rather than best-effort-log -- the caller
 * ({@link com.dalai.llama.creativeplanning.service.requirement.IdeaGenerationJobService}) rolls
 * back the local PENDING row on this failure so no orphan is left behind.
 */
@Slf4j
@Component
public class ChatJobRequestedPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final String topic;

    public ChatJobRequestedPublisher(
            KafkaTemplate<String, Object> kafkaTemplate,
            @Value("${creative-planning.llm-gateway.job-requested-topic}") String topic
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    public void publish(ChatJobRequestedEvent event) {
        try {
            kafkaTemplate.send(topic, event.tenantId(), event);
        } catch (RuntimeException ex) {
            log.error("Failed to publish chat job requested tenantId={} idempotencyKey={} errorType={} errorMessage={}",
                    event.tenantId(), event.idempotencyKey(), ex.getClass().getSimpleName(), ex.getMessage());
            throw CreativePlanningException.upstream(
                    "Could not submit LLM job to llm-gateway (Kafka publish failed): " + ex.getMessage());
        }
    }
}
