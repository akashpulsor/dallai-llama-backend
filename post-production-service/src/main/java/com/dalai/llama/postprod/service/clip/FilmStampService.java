package com.dalai.llama.postprod.service.clip;

import com.dalai.llama.postprod.domain.entity.FilmRender;
import com.dalai.llama.postprod.kafka.FilmStampRequestedEvent;
import com.dalai.llama.postprod.kafka.FilmStampRequestedPublisher;
import com.dalai.llama.postprod.repository.FilmRenderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Every published film gets a copy with the Dalaillama mark burned in; uploads from the platform
 * (to the creator's channel and to Dalaillama's) use only that copy.
 *
 * <p>A sweep, not a hook on publishing: it also covers films published before stamping existed,
 * a Kafka send that never landed and a stamp that failed, with no change to the publish path. The
 * delay it adds (up to a minute) is nothing next to an upload.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FilmStampService {

    static final String PENDING = "PENDING";
    static final String DONE = "DONE";
    static final String FAILED = "FAILED";
    static final int MAX_ATTEMPTS = 3;
    private static final Duration STUCK_AFTER = Duration.ofMinutes(30);
    private static final Duration RETRY_AFTER = Duration.ofMinutes(10);
    private static final int BATCH = 20;

    private final FilmRenderRepository renders;
    private final FilmStampRequestedPublisher publisher;
    private final FfmpegClipProcessor ffmpeg;
    private final ClipObjectStore objectStore;

    /** Queues every published film that is due a stamp. Returns how many were queued. */
    @Scheduled(fixedDelayString = "${post-production.film-stamp.sweep-ms:60000}", initialDelayString = "${post-production.film-stamp.sweep-ms:60000}")
    public int sweep() {
        OffsetDateTime now = OffsetDateTime.now();
        List<FilmRender> due = renders.findDueForStamp(now.minus(STUCK_AFTER), now.minus(RETRY_AFTER), MAX_ATTEMPTS, BATCH);
        for (FilmRender render : due) {
            render.setStampStatus(PENDING);
            render.setStampRequestedAt(now);
            render.setStampAttempts(render.getStampAttempts() + 1);
            renders.save(render);
            try {
                publisher.publish(new FilmStampRequestedEvent(render.getRenderId(), render.getTenantId(), render.getProjectId()));
            } catch (RuntimeException ex) {
                log.warn("Could not queue the stamp for renderId={} (the next sweep retries): {}", render.getRenderId(), ex.getMessage());
            }
        }
        return due.size();
    }

    /** Consumer side: burns the mark in and stores the stamped copy next to the film. */
    public void stamp(UUID renderId) {
        FilmRender render = renders.findById(renderId).orElse(null);
        if (render == null || render.getStampedObjectKey() != null || render.getObjectKey() == null) return;
        Path workDir = ffmpeg.createWorkDir("stamp-" + renderId);
        try {
            Path mark = workDir.resolve("stamp.png");
            try (InputStream in = new ClassPathResource("stamp/dalaillama-stamp.png").getInputStream()) {
                Files.copy(in, mark, StandardCopyOption.REPLACE_EXISTING);
            }
            int[] geometry = FfmpegClipProcessor.stampGeometry(render.getWidth() == null ? 1080 : render.getWidth());
            Path output = workDir.resolve("stamped.mp4");
            ffmpeg.stamp(objectStore.internalPresignedUrl(render.getBucket(), render.getObjectKey()), mark, geometry[0], geometry[1], output);
            String key = stampedKey(render);
            objectStore.upload(key, output, "video/mp4");
            render.setStampedObjectKey(key);
            render.setStampStatus(DONE);
            render.setStampError(null);
            renders.save(render);
            log.info("Stamped film renderId={} projectId={}", renderId, render.getProjectId());
        } catch (IOException | RuntimeException ex) {
            log.warn("Stamping failed renderId={} attempt={}: {}", renderId, render.getStampAttempts(), ex.getMessage());
            render.setStampStatus(FAILED);
            render.setStampError(ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
            renders.save(render);
        } finally {
            ffmpeg.deleteQuietly(workDir);
        }
    }

    /** Short-lived URL of the stamped copy, or null while it isn't ready. */
    public String stampedUrl(FilmRender render) {
        return render.getStampedObjectKey() == null ? null : objectStore.presignedUrl(objectStore.bucket(), render.getStampedObjectKey());
    }

    static String stampedKey(FilmRender render) {
        return "films/%s/%s-stamped.mp4".formatted(render.getProjectId(), render.getRenderId());
    }
}
