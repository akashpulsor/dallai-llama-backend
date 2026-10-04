package com.dalai.llama.postprod.kafka;

import com.dalai.llama.postprod.domain.FrameExtractionMode;
import com.dalai.llama.postprod.dto.FrameExtractionRequestedEvent;
import com.dalai.llama.postprod.service.ShotFrameService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Frame extraction off the request thread, for segmenting a whole clip (SAMPLE / ALL) without
 * holding a request open. The video studio's last-frame step does not come through here -- one
 * frame is quick enough to answer directly.
 *
 * <p>A failed extraction is logged and dropped rather than retried: the frames are a derivative of
 * a clip that is still there, and asking again is cheap.
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
        FrameExtractionMode mode = event.mode() == null ? FrameExtractionMode.LAST_FRAME : event.mode();
        log.info("Frame extraction requested tenantId={} shotId={} mode={}", event.tenantId(), event.shotId(), mode);
        try {
            shotFrameService.extract(event.tenantId(), event.projectId(), event.shotId(), mode, event.sampleFps());
        } catch (RuntimeException ex) {
            log.warn("Frame extraction failed shotId={} mode={}: {}", event.shotId(), mode, ex.getMessage());
        }
    }
}
