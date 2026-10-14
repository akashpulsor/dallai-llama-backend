package com.dalai.llama.postprod.kafka;

import com.dalai.llama.postprod.service.clip.ClipProcessingException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/** Publishes {@link FilmUpscaleRequestedEvent}. A failure throws, so the render is marked failed
 * rather than left QUEUED with nothing behind it. */
@Component
public class FilmUpscaleRequestedPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final String topic;

    public FilmUpscaleRequestedPublisher(KafkaTemplate<String, Object> kafkaTemplate,
                                         @Value("${post-production.film-upscale.requested-topic}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    public void publish(FilmUpscaleRequestedEvent event) {
        try {
            kafkaTemplate.send(topic, event.tenantId(), event);
        } catch (RuntimeException ex) {
            throw new ClipProcessingException("Could not queue the film upscale: " + ex.getMessage(), ex);
        }
    }
}
