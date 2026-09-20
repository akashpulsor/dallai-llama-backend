package com.dalai.llama.llmgateway.kafka;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes {@link ChatJobRequestedEvent} to {@code llm.job.requested} from inside llm-gateway.
 *
 * <p>Normally callers (pre-production-service etc.) are the ones publishing on this topic and
 * llm-gateway only consumes. The admin retry path is the exception: an operator asks llm-gateway
 * to re-run a job it already has stored, and the simplest way to reuse the existing consumer +
 * idempotency + billing pipeline is to publish a fresh event to the same topic the consumer is
 * already listening on. Mirrors {@link ChatJobCompletedPublisher}'s shape.
 */
@Slf4j
@Component
public class ChatJobRequestedPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final String topic;

    public ChatJobRequestedPublisher(
            KafkaTemplate<String, Object> kafkaTemplate,
            @Value("${llm-gateway.job-requested-kafka-topic:llm.job.requested}") String topic
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    public void publish(ChatJobRequestedEvent event) {
        kafkaTemplate.send(topic, event.tenantId(), event);
    }
}
