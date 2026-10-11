package com.dalai.llama.postprod.kafka;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/** Queues a film for FilmStampConsumer. A failed send is only logged: the row stays due and the
 * next sweep asks again. */
@Slf4j
@Component
public class FilmStampRequestedPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final String topic;

    public FilmStampRequestedPublisher(KafkaTemplate<String, Object> kafkaTemplate,
                                       @Value("${post-production.film-stamp.requested-topic}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    public void publish(FilmStampRequestedEvent event) {
        kafkaTemplate.send(topic, event.projectId().toString(), event);
    }
}
