package com.dalai.llama.videogen.kafka;

import com.dalai.llama.videogen.service.VideoGenException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes {@link VideoGenerationRequestedEvent} for this service's own worker side to consume.
 *
 * <p>A publish failure is fatal to the approve that raised it, as on the prepare-batch publisher:
 * a job left PROCESSING with no event behind it is a shot that will never render and never fail,
 * so this throws and the caller marks the job failed rather than leaving it hanging.
 */
@Slf4j
@Component
public class VideoGenerationRequestedPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final String topic;

    public VideoGenerationRequestedPublisher(
            KafkaTemplate<String, Object> kafkaTemplate,
            @Value("${video-gen.generation.requested-topic}") String topic
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    public void publish(VideoGenerationRequestedEvent event) {
        try {
            // Keyed by TENANT, not by project or job.
            //
            // A generation call is the expensive, rate-limited thing this platform does, and the
            // limits that matter are per tenant at the model provider. Keying by tenant puts all of
            // one tenant's renders on one partition, so they queue behind each other in submission
            // order and one busy tenant occupies one consumer rather than every consumer -- which
            // is what keeps a project of forty shots from starving everyone else's single shot.
            kafkaTemplate.send(topic, event.tenantId(), event);
        } catch (RuntimeException ex) {
            log.error("Failed to publish video-generation requested jobId={} tenantId={} errorType={} errorMessage={}",
                    event.jobId(), event.tenantId(), ex.getClass().getSimpleName(), ex.getMessage());
            throw VideoGenException.upstream(
                    "Could not queue this shot for generation (Kafka publish failed): " + ex.getMessage(), ex);
        }
    }
}
