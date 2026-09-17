package com.dalai.llama.videogen.kafka;

import com.dalai.llama.videogen.domain.JobStatus;
import com.dalai.llama.videogen.domain.entity.DubJob;
import com.dalai.llama.videogen.repository.DubJobRepository;
import com.dalai.llama.videogen.service.DubJobService;
import com.dalai.llama.videogen.web.TenantContext;
import com.dalai.llama.videogen.web.TenantContextHolder;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Records a shot's line off the request thread.
 *
 * <p>Same shape as the generation consumer, for the same reasons: the consumer group is the
 * concurrency control, the work is durable in dub_job, and a restart re-delivers rather than drops.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DubRequestedConsumer {

    private final ObjectMapper objectMapper;
    private final DubJobRepository dubJobRepository;
    private final DubJobService dubJobService;

    @KafkaListener(
            topics = "${video-gen.dub.requested-topic}",
            groupId = "${video-gen.dub.requested-consumer-group:video-generation-service-dub}"
    )
    public void onMessage(String payload) {
        DubRequestedEvent event;
        try {
            event = objectMapper.readValue(payload, DubRequestedEvent.class);
        } catch (Exception ex) {
            log.error("Dropping unparseable DubRequestedEvent payload: {}", ex.getMessage(), ex);
            return;
        }

        Optional<DubJob> maybe = dubJobRepository.findById(event.jobId());
        if (maybe.isEmpty()) {
            log.warn("Dropping dub event for unknown jobId={}", event.jobId());
            return;
        }
        JobStatus status = maybe.get().getStatus();
        if (status != null && (status.isTerminal() || status == JobStatus.PROCESSING)) {
            // Finished, or already being recorded by another consumer. Re-running would bill the
            // voice provider a second time for the same line.
            log.info("Ignoring redelivered dub jobId={} already {}", event.jobId(), status);
            return;
        }

        // TenantContextHolder is a ThreadLocal the servlet filter fills, so a consumer thread has
        // none and every llm-gateway call downstream would fail on the missing tenant.
        TenantContext ctx = new TenantContext(UUID.fromString(event.tenantId()), event.userId());
        TenantContextHolder.set(ctx);
        try {
            dubJobService.run(ctx, event.jobId(), event.projectId(), event.shotId(), event.text());
        } catch (RuntimeException ex) {
            // run() already recorded the failure on the row the page polls.
            log.warn("Dub jobId={} shotId={} failed: {}", event.jobId(), event.shotId(), ex.getMessage());
        } finally {
            TenantContextHolder.clear();
        }
    }
}
