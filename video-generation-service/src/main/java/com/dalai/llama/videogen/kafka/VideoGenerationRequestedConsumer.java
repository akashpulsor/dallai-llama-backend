package com.dalai.llama.videogen.kafka;

import com.dalai.llama.videogen.domain.entity.VideoGenJob;
import com.dalai.llama.videogen.repository.VideoGenJobRepository;
import com.dalai.llama.videogen.service.dialoguefit.DialogueFitChoice;
import com.dalai.llama.videogen.service.ShotGenerationOrchestrator;
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
 * Renders an approved shot off the request thread.
 *
 * <p>Approving used to render inline and hold the HTTP connection for the whole thing -- minutes,
 * not seconds. Every recurring problem on this page came from that one fact: the gateway answered
 * 504 while the render carried on, its retries re-dispatched a non-idempotent call and billed it
 * again, a closed tab lost the result of work that had already been paid for, and the button stayed
 * live long enough to be pressed twice. None of those are fixable while the render is the response.
 *
 * <p>Same shape as {@link PrepareBatchRequestedConsumer}: no thread pool, because the consumer group
 * IS the concurrency mechanism. A pool loses its queue when the pod restarts, cannot be retuned
 * without a redeploy, and gives two replicas no way to agree on who is rendering what. Here the work
 * is durable in video_gen_job, concurrency is one consumer per partition, and a restart re-delivers.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VideoGenerationRequestedConsumer {

    private final ObjectMapper objectMapper;
    private final VideoGenJobRepository videoGenJobRepository;
    private final ShotGenerationOrchestrator orchestrator;

    @KafkaListener(
            topics = "${video-gen.generation.requested-topic}",
            groupId = "${video-gen.generation.requested-consumer-group:video-generation-service-generation}"
    )
    public void onMessage(String payload) {
        VideoGenerationRequestedEvent event;
        try {
            event = objectMapper.readValue(payload, VideoGenerationRequestedEvent.class);
        } catch (Exception ex) {
            log.error("Dropping unparseable VideoGenerationRequestedEvent payload: {}", ex.getMessage(), ex);
            return;
        }

        Optional<VideoGenJob> maybeJob = videoGenJobRepository.findById(event.jobId());
        if (maybeJob.isEmpty()) {
            log.warn("Dropping generation event for unknown jobId={}", event.jobId());
            return;
        }
        VideoGenJob job = maybeJob.get();
        if (job.getStatus() != null && job.getStatus() == com.dalai.llama.videogen.domain.JobStatus.PROCESSING) {
            // Already being rendered by another consumer, or by this one before a redelivery.
            log.info("Ignoring generation jobId={} already rendering", job.getJobId());
            return;
        }
        if (job.getStatus() != null && job.getStatus().isTerminal()) {
            // A redelivery after the work committed but before the offset did. Re-rendering would
            // bill the provider a second time for a clip that already exists.
            log.info("Ignoring redelivered generation jobId={} already {}", job.getJobId(), job.getStatus());
            return;
        }

        // TenantContextHolder is a ThreadLocal the servlet filter fills, so a consumer thread has
        // none and every llm-gateway call downstream would fail on the missing tenant. Rebuilt from
        // the event and cleared after, since consumer threads are reused.
        TenantContext ctx = new TenantContext(UUID.fromString(event.tenantId()), event.userId());
        TenantContextHolder.set(ctx);
        try {
            DialogueFitChoice fit = event.fitChoice() == null ? DialogueFitChoice.EXTEND : event.fitChoice();
            orchestrator.runGeneration(ctx, event.jobId(), fit);
        } catch (RuntimeException ex) {
            // runGeneration already records the failure on the job row, which is what the page
            // polls; this is the operator-facing line.
            log.warn("Generation jobId={} projectId={} failed: {}",
                    event.jobId(), event.projectId(), ex.getMessage());
        } finally {
            TenantContextHolder.clear();
        }
    }
}
