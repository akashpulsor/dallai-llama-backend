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

    /** Generation events are keyed by TENANT, so partitions are how two tenants render at the same
     * time while one tenant's shots queue behind each other in submission order. That ordering is
     * the point: a generation call is the expensive, provider-rate-limited thing here, and a
     * project of forty shots must not occupy every consumer and starve another tenant's single
     * shot. */
    /** Dub events are keyed by tenant for the same reason generation events are: synthesis is a
     * provider call with per-tenant limits, and one tenant's forty lines must not occupy every
     * consumer. */
    @Bean
    public NewTopic dubRequestedTopic(@Value("${video-gen.dub.requested-topic}") String topic) {
        return new NewTopic(topic, 3, (short) 1);
    }

    @Bean
    public NewTopic generationRequestedTopic(@Value("${video-gen.generation.requested-topic}") String topic) {
        return new NewTopic(topic, 3, (short) 1);
    }
}
