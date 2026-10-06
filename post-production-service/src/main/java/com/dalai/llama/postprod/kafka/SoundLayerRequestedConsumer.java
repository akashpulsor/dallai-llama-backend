package com.dalai.llama.postprod.kafka;

import com.dalai.llama.postprod.service.sound.SoundLayerProcessor;
import com.dalai.llama.postprod.web.TenantContext;
import com.dalai.llama.postprod.web.TenantContextHolder;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Where sound layers are prepared -- generated or picked up, probed with ffmpeg and stored -- one
 * at a time per consumer, so a burst of sounds never runs ffmpeg side by side on the heap. */
@Slf4j
@Component
@RequiredArgsConstructor
public class SoundLayerRequestedConsumer {

    private final SoundLayerProcessor processor;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = "${post-production.sound-layer.requested-topic}",
            groupId = "${post-production.sound-layer.requested-consumer-group:post-production-service-sound-layer}"
    )
    public void onMessage(String payload) {
        SoundLayerRequestedEvent event;
        try {
            event = objectMapper.readValue(payload, SoundLayerRequestedEvent.class);
        } catch (Exception ex) {
            log.error("Dropping unparseable sound layer payload: {}", ex.getMessage(), ex);
            return;
        }
        // A consumer thread has no servlet-filled tenant; rebuilt from the event, cleared after
        // because consumer threads are reused.
        TenantContextHolder.set(new TenantContext(event.tenantId(), event.userId()));
        try {
            processor.process(event.layerId());
        } finally {
            TenantContextHolder.clear();
        }
    }
}
