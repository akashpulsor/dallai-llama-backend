package com.dalai.llama.videogen.kafka;

import com.dalai.llama.videogen.service.VideoGenException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes {@link DubRequestedEvent} for this service's own worker side.
 *
 * <p>Keyed by tenant, like generation: synthesis is a provider call with per-tenant limits, and one
 * tenant's queue of forty lines must not occupy every consumer.
 */
@Slf4j
@Component
public class DubRequestedPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final String topic;

    public DubRequestedPublisher(
            KafkaTemplate<String, Object> kafkaTemplate,
            @Value("${video-gen.dub.requested-topic}") String topic
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    public void publish(DubRequestedEvent event) {
        try {
            kafkaTemplate.send(topic, event.tenantId(), event);
        } catch (RuntimeException ex) {
            log.error("Failed to publish dub requested jobId={} shotId={} errorMessage={}",
                    event.jobId(), event.shotId(), ex.getMessage());
            throw VideoGenException.upstream("Could not queue this dub: " + ex.getMessage(), ex);
        }
    }
}
