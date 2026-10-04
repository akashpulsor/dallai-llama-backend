package com.dalai.llama.postprod.kafka;

import com.dalai.llama.postprod.dto.ShotConformDtos;
import com.dalai.llama.postprod.service.clip.ShotConformService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Where conforms run: slowing, interpolating and trimming clips, one at a time per consumer. */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShotConformConsumer {

    private final ShotConformService conformService;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = "${post-production.conform.requested-topic}",
            groupId = "${post-production.conform.requested-consumer-group:post-production-service-conform}"
    )
    public void onMessage(String payload) {
        ShotConformDtos.RequestedEvent event;
        try {
            event = objectMapper.readValue(payload, ShotConformDtos.RequestedEvent.class);
        } catch (Exception ex) {
            log.error("Dropping unparseable conform payload: {}", ex.getMessage(), ex);
            return;
        }
        conformService.process(event.requestId());
    }
}
