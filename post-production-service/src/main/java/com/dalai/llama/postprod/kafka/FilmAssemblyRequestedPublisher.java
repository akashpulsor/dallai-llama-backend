package com.dalai.llama.postprod.kafka;

import com.dalai.llama.postprod.service.clip.ClipProcessingException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes {@link FilmAssemblyRequestedEvent} for this service's own worker side.
 *
 * <p>A publish failure is fatal to the request that raised it. A film_render row left QUEUED with no
 * event behind it is a film that will never be joined and never fail, which is worse than an error
 * the creator can see and retry -- so this throws and the caller marks the render failed.
 */
@Slf4j
@Component
public class FilmAssemblyRequestedPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final String topic;

    public FilmAssemblyRequestedPublisher(
            KafkaTemplate<String, Object> kafkaTemplate,
            @Value("${post-production.film.requested-topic}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    public void publish(FilmAssemblyRequestedEvent event) {
        try {
            kafkaTemplate.send(topic, event.tenantId(), event);
        } catch (RuntimeException ex) {
            log.error("Failed to publish film-assembly requested renderId={} tenantId={} errorMessage={}",
                    event.renderId(), event.tenantId(), ex.getMessage());
            throw new ClipProcessingException(
                    "Could not queue the film for joining: " + ex.getMessage(), ex);
        }
    }
}
