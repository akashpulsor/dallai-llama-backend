package com.dalai.llama.postprod.kafka;



import com.dalai.llama.postprod.dto.FrameExtractionRequestedEvent;
import com.dalai.llama.postprod.service.ShotFrameService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class FrameExtractionConsumer {

    private final ShotFrameService shotFrameService;

    @KafkaListener(
            topics = "${post-production.frame-extraction.requested-topic}",
            groupId = "${post-production.frame-extraction.consumer-group}"
    )
    public void consume(FrameExtractionRequestedEvent event) {

        log.info(
                "Frame extraction request tenantId={} shotId={}",
                event.tenantId(),
                event.shotId()
        );

        shotFrameService.extractFrames(
                event.tenantId(),
                event.shotId()
        );
    }
}