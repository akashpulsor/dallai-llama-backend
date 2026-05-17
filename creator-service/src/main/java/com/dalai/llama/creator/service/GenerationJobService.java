package com.dalai.llama.creator.service;

import com.dalai.llama.creator.config.CreatorProperties;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class GenerationJobService {

    private final CreatorProperties properties;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    public GenerationJobService(CreatorProperties properties, KafkaTemplate<String, Object> kafkaTemplate) {
        this.properties = properties;
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publishGenerationJob(String jobId, Map<String, Object> payload) {
        kafkaTemplate.send(properties.getKafka().getGenerationJobsTopic(), jobId, payload);
    }
}
