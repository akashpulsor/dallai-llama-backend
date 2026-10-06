package com.dalai.llama.postprod.kafka;

import com.dalai.llama.postprod.service.PostProductionException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/** Queues a sound layer for SoundLayerRequestedConsumer, keyed by project so one project's sounds
 * are prepared in the order they were asked for. */
@Slf4j
@Component
public class SoundLayerRequestedPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final String topic;

    public SoundLayerRequestedPublisher(KafkaTemplate<String, Object> kafkaTemplate,
                                        @Value("${post-production.sound-layer.requested-topic}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    public void publish(SoundLayerRequestedEvent event) {
        try {
            kafkaTemplate.send(topic, event.projectId().toString(), event);
        } catch (RuntimeException ex) {
            log.error("Failed to publish sound layer layerId={} projectId={}: {}", event.layerId(), event.projectId(), ex.getMessage());
            throw PostProductionException.upstream("Could not queue the sound: " + ex.getMessage(), ex);
        }
    }
}
