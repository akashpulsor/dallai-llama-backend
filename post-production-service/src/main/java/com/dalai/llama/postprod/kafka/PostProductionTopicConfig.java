package com.dalai.llama.postprod.kafka;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * This service's own topics, declared so a fresh environment does not depend on broker-side
 * auto-creation.
 *
 * <p>Three partitions, matching every other topic on the platform. Keyed by TENANT:
 * joining a film is a long ffmpeg run, and keying by tenant puts one tenant's work on one partition
 * where it queues in submission order -- which is what stops a project of forty shots occupying
 * every consumer and starving another tenant's single job.
 */
@Configuration
public class PostProductionTopicConfig {

    @Bean
    public NewTopic filmAssemblyRequestedTopic(
            @Value("${post-production.film.requested-topic}") String topic) {
        return new NewTopic(topic, 3, (short) 1);
    }
}
