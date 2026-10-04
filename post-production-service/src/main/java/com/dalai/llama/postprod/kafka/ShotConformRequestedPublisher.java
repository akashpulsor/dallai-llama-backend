package com.dalai.llama.postprod.kafka;

import com.dalai.llama.postprod.dto.ShotConformDtos;
import com.dalai.llama.postprod.service.PostProductionException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/** Queues a conform for ShotConformConsumer, keyed by shot so one shot's conforms run in order. */
@Slf4j
@Component
public class ShotConformRequestedPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final String topic;

    public ShotConformRequestedPublisher(KafkaTemplate<String, Object> kafkaTemplate,
                                         @Value("${post-production.conform.requested-topic}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    public void publish(ShotConformDtos.RequestedEvent event) {
        try {
            kafkaTemplate.send(topic, event.shotId().toString(), event);
        } catch (RuntimeException ex) {
            log.error("Failed to publish conform requestId={} shotId={}: {}", event.requestId(), event.shotId(), ex.getMessage());
            throw PostProductionException.upstream("Could not queue the conform: " + ex.getMessage(), ex);
        }
    }
}
