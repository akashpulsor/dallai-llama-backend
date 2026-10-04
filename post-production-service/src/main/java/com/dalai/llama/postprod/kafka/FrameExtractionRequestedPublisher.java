package com.dalai.llama.postprod.kafka;

import com.dalai.llama.postprod.dto.FrameExtractionRequestedEvent;
import com.dalai.llama.postprod.service.PostProductionException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/** Queues a frame extraction for FrameExtractionConsumer. Keyed by shot, so two requests for one
 * shot are worked in order on the same partition. */
@Slf4j
@Component
public class FrameExtractionRequestedPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final String topic;

    public FrameExtractionRequestedPublisher(
            KafkaTemplate<String, Object> kafkaTemplate,
            @Value("${post-production.frame-extraction.requested-topic}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    public void publish(FrameExtractionRequestedEvent event) {
        try {
            kafkaTemplate.send(topic, event.shotId().toString(), event);
        } catch (RuntimeException ex) {
            log.error("Failed to publish frame extraction requestId={} shotId={}: {}",
                    event.requestId(), event.shotId(), ex.getMessage());
            throw PostProductionException.upstream("Could not queue the frame extraction: " + ex.getMessage(), ex);
        }
    }
}
