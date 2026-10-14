package com.dalai.llama.postprod.kafka;

import com.dalai.llama.postprod.service.clip.FilmUpscaleService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Where films are upscaled, one at a time per consumer -- Topaz runs for minutes. */
@Slf4j
@Component
@RequiredArgsConstructor
public class FilmUpscaleRequestedConsumer {

    private final FilmUpscaleService upscaleService;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = "${post-production.film-upscale.requested-topic}",
            groupId = "${post-production.film-upscale.requested-consumer-group:post-production-service-film-upscale}"
    )
    public void onMessage(String payload) {
        FilmUpscaleRequestedEvent event;
        try {
            event = objectMapper.readValue(payload, FilmUpscaleRequestedEvent.class);
        } catch (Exception ex) {
            log.error("Dropping unparseable film-upscale payload: {}", ex.getMessage(), ex);
            return;
        }
        upscaleService.upscale(event.renderId());
    }
}
