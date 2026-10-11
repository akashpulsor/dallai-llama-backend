package com.dalai.llama.postprod.kafka;

import com.dalai.llama.postprod.service.clip.FilmStampService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Where films get their Dalaillama stamp -- ffmpeg off the request thread, one at a time. */
@Slf4j
@Component
@RequiredArgsConstructor
public class FilmStampConsumer {

    private final FilmStampService stamps;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = "${post-production.film-stamp.requested-topic}",
            groupId = "${post-production.film-stamp.requested-consumer-group:post-production-service-film-stamp}"
    )
    public void onMessage(String payload) {
        FilmStampRequestedEvent event;
        try {
            event = objectMapper.readValue(payload, FilmStampRequestedEvent.class);
        } catch (Exception ex) {
            log.error("Dropping unparseable film stamp payload: {}", ex.getMessage(), ex);
            return;
        }
        stamps.stamp(event.renderId());
    }
}
