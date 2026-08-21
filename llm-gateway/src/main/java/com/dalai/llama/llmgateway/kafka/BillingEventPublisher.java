package com.dalai.llama.llmgateway.kafka;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class BillingEventPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final String topic;

    public BillingEventPublisher(
            KafkaTemplate<String, Object> kafkaTemplate,
            @Value("${llm-gateway.billing-kafka-topic}") String topic
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    public void publish(BillingEvent event) {
        try {
            kafkaTemplate.send(topic, event.tenantId(), event);
        } catch (RuntimeException ex) {
            // Best-effort for v1 (no outbox yet, see BillingEvent javadoc) -- a Kafka hiccup
            // must not fail an already-completed, already-billed-in-audit-log request back to
            // the caller.
            log.warn("Failed to publish billing event jobId={} errorType={} errorMessage={}",
                    event.jobId(), ex.getClass().getSimpleName(), ex.getMessage());
        }
    }
}
