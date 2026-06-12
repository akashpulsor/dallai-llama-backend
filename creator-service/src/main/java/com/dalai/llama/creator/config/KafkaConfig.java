package com.dalai.llama.creator.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;

@Configuration
@EnableKafka
public class KafkaConfig {

    private final CreatorProperties properties;

    public KafkaConfig(CreatorProperties properties) {
        this.properties = properties;
    }

    @Bean
    public NewTopic creatorGenerationJobsTopic() {
        return new NewTopic(properties.getKafka().getGenerationJobsTopic(), 3, (short) 1);
    }

    @Bean
    public NewTopic creatorStudioPolishJobsTopic() {
        int partitions = Math.max(1, properties.getAi().getStudioPolishTopicPartitions());
        return new NewTopic(properties.getAi().getStudioPolishJobsTopic(), partitions, (short) 1);
    }

    @Bean
    public NewTopic creatorBillingEventsTopic() {
        return new NewTopic(properties.getKafka().getBillingEventsTopic(), 3, (short) 1);
    }

    @Bean
    public NewTopic creatorAnalyticsEventsTopic() {
        return new NewTopic(properties.getKafka().getAnalyticsEventsTopic(), 3, (short) 1);
    }
}
