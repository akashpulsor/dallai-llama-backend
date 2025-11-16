package com.dalai.llama.blobmanager.kafka;

import com.dalai.llama.blobmanager.dto.RecordingEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class KafkaPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    /**
     * Topic prefix can be overridden:
     *  - Environment variable KAFKA_TOPIC_PREFIX
     *  - Defaults to "cdr.recording"
     */
    private final String topicPrefix =
            System.getenv().getOrDefault("KAFKA_TOPIC_PREFIX", "cdr.recording");

    /**
     * Publish recording metadata event.
     * @param tenantId - tenant namespace
     * @param callId - unique call ID
     * @param recordUrl - where the file is stored
     * @param objectId - unique storage object ID (used as Kafka message key)
     */
    public void publish(String tenantId, String callId, String recordUrl, String objectId) {
        try {
            RecordingEvent ev = new RecordingEvent(
                    tenantId,
                    callId,
                    recordUrl,
                    java.time.Instant.now()
            );

            // Topic is tenant scoped
            String topic = topicPrefix + "." + tenantId;

            log.info("📤 Publishing recording event to topic={} key={} call={} url={}",
                    topic, objectId, callId, recordUrl);

            kafkaTemplate.send(topic, objectId, ev);

        } catch (Exception e) {
            log.error("❌ Failed to publish Kafka event for call {} tenant {}: {}",
                    callId, tenantId, e.getMessage(), e);
        }
    }
}
