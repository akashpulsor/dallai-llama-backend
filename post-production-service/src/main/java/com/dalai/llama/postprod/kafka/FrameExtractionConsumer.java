package com.dalai.llama.postprod.kafka;

import com.dalai.llama.postprod.dto.FrameExtractionRequestedEvent;
import com.dalai.llama.postprod.service.ShotFrameService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Where frame extraction actually runs. Requests arrive from ShotFrameService.request; the consumer
 * group is the concurrency limit -- one clip download and one ffmpeg process at a time per consumer
 * -- however many people ask at once.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FrameExtractionConsumer {

    private final ShotFrameService shotFrameService;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = "${post-production.frame-extraction.requested-topic}",
            groupId = "${post-production.frame-extraction.requested-consumer-group:post-production-service-frame-extraction}"
    )
    public void onMessage(String payload) {
        FrameExtractionRequestedEvent event;
        try {
            event = objectMapper.readValue(payload, FrameExtractionRequestedEvent.class);
        } catch (Exception ex) {
            log.error("Dropping unparseable FrameExtractionRequestedEvent payload: {}", ex.getMessage(), ex);
            return;
        }
        // process() records success or failure on the request row the caller polls, so nothing
        // escapes here to make Kafka redeliver a request that has already been answered.
        shotFrameService.process(event.requestId());
    }
}
