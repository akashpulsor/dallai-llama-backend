package com.dalai.llama.preprod.kafka;

import com.dalai.llama.preprod.service.PreProductionException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes {@link ChatJobRequestedEvent} to {@code llm.job.requested} for llm-gateway's async
 * worker to consume. Unlike the completion side, a publish failure here IS a fatal error for
 * the calling flow -- no LLM call happens if we can't submit the job -- so we throw rather than
 * best-effort-log. The caller (e.g. {@link
 * com.dalai.llama.preprod.service.ShotListGenerationJobService}) should roll back the local job
 * row on this failure so no stranded PENDING row is left behind.
 */
@Slf4j
@Component
public class ChatJobRequestedPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final String topic;

    public ChatJobRequestedPublisher(
            KafkaTemplate<String, Object> kafkaTemplate,
            @Value("${pre-production.llm-gateway.job-requested-topic}") String topic
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
            throw PreProductionException.upstream(
                    "Could not submit LLM job to llm-gateway (Kafka publish failed): " + ex.getMessage(), ex);
        }
    }
}
