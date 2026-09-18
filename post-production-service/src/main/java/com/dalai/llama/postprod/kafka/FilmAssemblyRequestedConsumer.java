package com.dalai.llama.postprod.kafka;

import com.dalai.llama.postprod.domain.entity.FilmRender;
import com.dalai.llama.postprod.repository.FilmRenderRepository;
import com.dalai.llama.postprod.service.clip.FilmAssemblyService;
import com.dalai.llama.postprod.web.TenantContext;
import com.dalai.llama.postprod.web.TenantContextHolder;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Joins a film off the request thread.
 *
 * <p>ffmpeg over every shot in a project runs for minutes, so the request that asks for it cannot
 * also be the one that waits for it: on the gateway's timeout it would answer 504 while the join
 * carried on, and the retries would start the whole thing again. The consumer group is the
 * concurrency mechanism -- no thread pool, because a pool loses its queue when the pod restarts and
 * gives two replicas no way to agree on who is joining what.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FilmAssemblyRequestedConsumer {

    private final ObjectMapper objectMapper;
    private final FilmRenderRepository filmRenderRepository;
    private final FilmAssemblyService filmAssemblyService;

    /** How many times a renderId is looked up before the event is written off. */
    static final int LOOKUP_ATTEMPTS = 3;

    /** Gap between those attempts -- long enough to outlast a commit landing late, short enough
     * that a genuinely unknown renderId does not hold the partition. */
    private static final long LOOKUP_RETRY_MILLIS = 500;

    private Optional<FilmRender> findWithRetry(UUID renderId) {
        for (int attempt = 1; attempt <= LOOKUP_ATTEMPTS; attempt++) {
            Optional<FilmRender> found = filmRenderRepository.findById(renderId);
            if (found.isPresent()) {
                return found;
            }
            if (attempt < LOOKUP_ATTEMPTS) {
                log.info("No row yet for renderId={}, re-reading ({}/{})", renderId, attempt, LOOKUP_ATTEMPTS);
                try {
                    Thread.sleep(LOOKUP_RETRY_MILLIS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return Optional.empty();
                }
            }
        }
        return Optional.empty();
    }

    @KafkaListener(
            topics = "${post-production.film.requested-topic}",
            groupId = "${post-production.film.requested-consumer-group:post-production-service-film}"
    )
    public void onMessage(String payload) {
        FilmAssemblyRequestedEvent event;
        try {
            event = objectMapper.readValue(payload, FilmAssemblyRequestedEvent.class);
        } catch (Exception ex) {
            log.error("Dropping unparseable FilmAssemblyRequestedEvent payload: {}", ex.getMessage(), ex);
            return;
        }

        // Re-read before giving up. The publisher now sends only after its transaction commits, so
        // the row should always be here -- but "not found" used to be treated as final, and that is
        // what turned a 300ms timing window into a film lost for ever: the listener returned
        // normally, Kafka committed the offset, and the event was gone. Returning without doing the
        // work is the one outcome this must never reach cheaply.
        Optional<FilmRender> maybe = findWithRetry(event.renderId());
        if (maybe.isEmpty()) {
            log.warn("Dropping film-assembly event for unknown renderId={} after {} attempts",
                    event.renderId(), LOOKUP_ATTEMPTS);
            return;
        }
        if (maybe.get().getStatus() != null && maybe.get().getStatus().isTerminal()) {
            // A redelivery after the work committed but before the offset did.
            log.info("Ignoring redelivered film assembly renderId={} already {}",
                    event.renderId(), maybe.get().getStatus());
            return;
        }

        // TenantContextHolder is a ThreadLocal the servlet filter fills, so a consumer thread has
        // none and every downstream call would fail on the missing tenant. Rebuilt from the event
        // and cleared after, since consumer threads are reused.
        TenantContextHolder.set(new TenantContext(UUID.fromString(event.tenantId()), event.userId()));
        try {
            filmAssemblyService.assemble(event.renderId());
        } catch (RuntimeException ex) {
            // assemble() already recorded the failure on the row the page polls; this is the
            // operator-facing line.
            log.warn("Film assembly renderId={} projectId={} failed: {}",
                    event.renderId(), event.projectId(), ex.getMessage());
        } finally {
            TenantContextHolder.clear();
        }
    }
}
