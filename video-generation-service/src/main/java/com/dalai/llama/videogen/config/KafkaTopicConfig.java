package com.dalai.llama.videogen.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Declares this service's own topics so a fresh environment does not depend on broker-side
 * auto-creation. Three partitions, matching the other services' topics: prepare-batch events are
 * keyed by project, so partitions are how two batches for different projects run concurrently
 * while batches for one project stay ordered.
 */
@Configuration
public class KafkaTopicConfig {

    @Bean
    public NewTopic prepareRequestedTopic(@Value("${video-gen.prepare.requested-topic}") String topic) {
        return new NewTopic(topic, 3, (short) 1);
    }
}
