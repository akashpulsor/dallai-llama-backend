package com.dalai.llama.llmgateway.kafka;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes {@link ChatJobCompletedEvent} to {@code llm.job.completed}. Modeled on
 * {@link BillingEventPublisher} -- best-effort; the terminal state is already persisted to
 * {@code llm_job} at this point, so a lost Kafka publish means callers must fall back to
 * polling {@link com.dalai.llama.llmgateway.repository.LlmJobRepository} rather than either
 * side blocking retry logic on Kafka availability.
 */
@Slf4j
@Component
public class ChatJobCompletedPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final String topic;

    public ChatJobCompletedPublisher(
            KafkaTemplate<String, Object> kafkaTemplate,
            @Value("${llm-gateway.job-completed-kafka-topic:llm.job.completed}") String topic
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    public void publish(ChatJobCompletedEvent event) {
        try {
            kafkaTemplate.send(topic, event.tenantId(), event);
        } catch (RuntimeException ex) {
            log.warn("Failed to publish chat job completed jobId={} status={} errorType={} errorMessage={}",
                    event.jobId(), event.status(), ex.getClass().getSimpleName(), ex.getMessage());
        }
    }
}
