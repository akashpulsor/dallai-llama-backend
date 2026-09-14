package com.dalai.llama.videogen.kafka;

import com.dalai.llama.videogen.service.VideoGenException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes {@link PrepareBatchRequestedEvent} for this service's own worker side to consume.
 *
 * <p>A publish failure is fatal to the calling flow, as it is on pre-production-service's
 * equivalent publisher: no batch runs if the event cannot be submitted, so this throws rather
 * than best-effort-logging, and the caller fails the job row instead of leaving a PENDING row
 * nothing will ever pick up.
 */
@Slf4j
@Component
public class PrepareBatchRequestedPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final String topic;

    public PrepareBatchRequestedPublisher(
            KafkaTemplate<String, Object> kafkaTemplate,
            @Value("${video-gen.prepare.requested-topic}") String topic
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    public void publish(PrepareBatchRequestedEvent event) {
        try {
            // Keyed by project so every batch for a project lands on one partition and they are
            // processed in submission order rather than racing each other across partitions.
            kafkaTemplate.send(topic, event.projectId().toString(), event);
        } catch (RuntimeException ex) {
            log.error("Failed to publish prepare-batch requested jobId={} projectId={} errorType={} errorMessage={}",
                    event.jobId(), event.projectId(), ex.getClass().getSimpleName(), ex.getMessage());
            throw VideoGenException.upstream(
                    "Could not submit prepare batch (Kafka publish failed): " + ex.getMessage(), ex);
        }
    }
}
