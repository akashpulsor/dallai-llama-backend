package com.dalai.llama.postprod.service.clip;

import com.dalai.llama.postprod.domain.entity.UploadStamp;
import com.dalai.llama.postprod.kafka.FilmStampRequestedEvent;
import com.dalai.llama.postprod.kafka.FilmStampRequestedPublisher;
import com.dalai.llama.postprod.repository.UploadStampRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A creator's uploaded film, stamped with the Dalaillama mark before it goes on Dalaillama's
 * YouTube channel. tenant-service asks (an id of its own, so asking again is the same request) and
 * polls; a sweep queues what is due on the films' consumer, so stamps run one at a time.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UploadStampService {

    static final String PENDING = "PENDING";
    static final String DONE = "DONE";
    static final String FAILED = "FAILED";
    static final int MAX_ATTEMPTS = 3;
    private static final Duration STUCK_AFTER = Duration.ofMinutes(30);
    private static final Duration RETRY_AFTER = Duration.ofMinutes(10);
    private static final int BATCH = 20;

    private final UploadStampRepository stamps;
    private final FilmStampRequestedPublisher publisher;
    private final FfmpegClipProcessor ffmpeg;
    private final ClipObjectStore objectStore;

    public record StampRequest(UUID tenantId, String bucket, String objectKey, Integer width) {
    }

    /** status: PENDING until made (also while due), DONE with {@code stampedUrl}, or FAILED after
     * the last attempt with {@code error}. */
    public record StampView(String status, String stampedUrl, String error) {
    }

    /** Records the request once; asking again with the same id changes nothing. */
    public StampView request(UUID id, StampRequest request) {
        UploadStamp stamp = stamps.findById(id).orElseGet(() -> stamps.save(UploadStamp.builder()
                .id(id).tenantId(request.tenantId()).sourceBucket(request.bucket()).sourceKey(request.objectKey())
                .width(request.width()).attempts(0).build()));
        return view(stamp);
    }

    public Optional<StampView> find(UUID id) {
        return stamps.findById(id).map(this::view);
    }

    /** Queues every upload that is due a stamp. Returns how many were queued. */
    @Scheduled(fixedDelayString = "${post-production.film-stamp.sweep-ms:60000}", initialDelayString = "${post-production.film-stamp.sweep-ms:60000}")
    public int sweep() {
        OffsetDateTime now = OffsetDateTime.now();
        List<UploadStamp> due = stamps.findDue(now.minus(STUCK_AFTER), now.minus(RETRY_AFTER), MAX_ATTEMPTS, BATCH);
        for (UploadStamp stamp : due) {
            stamp.setStatus(PENDING);
            stamp.setRequestedAt(now);
            stamp.setAttempts(stamp.getAttempts() + 1);
            stamps.save(stamp);
            try {
                publisher.publish(FilmStampRequestedEvent.upload(stamp.getId(), stamp.getTenantId()));
            } catch (RuntimeException ex) {
                log.warn("Could not queue the stamp for upload {} (the next sweep retries): {}", stamp.getId(), ex.getMessage());
            }
        }
        return due.size();
    }

    /** Consumer side: burns the mark into the upload and stores the stamped copy in our bucket. */
    public void stamp(UUID id) {
        UploadStamp stamp = stamps.findById(id).orElse(null);
        if (stamp == null || stamp.getStampedObjectKey() != null) return;
        Path workDir = ffmpeg.createWorkDir("upload-stamp-" + id);
        try {
            Path output = FilmStampService.burnMark(ffmpeg, objectStore.internalPresignedUrl(stamp.getSourceBucket(), stamp.getSourceKey()),
                    stamp.getWidth(), workDir);
            String key = stampedKey(stamp);
            objectStore.upload(key, output, "video/mp4");
            stamp.setStampedObjectKey(key);
            stamp.setStatus(DONE);
            stamp.setError(null);
            stamps.save(stamp);
            log.info("Stamped upload {} tenant={}", id, stamp.getTenantId());
        } catch (IOException | RuntimeException ex) {
            log.warn("Stamping upload {} failed attempt={}: {}", id, stamp.getAttempts(), ex.getMessage());
            stamp.setStatus(FAILED);
            stamp.setError(truncate(ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()));
            stamps.save(stamp);
        } finally {
            ffmpeg.deleteQuietly(workDir);
        }
    }

    private StampView view(UploadStamp stamp) {
        if (stamp.getStampedObjectKey() != null) {
            return new StampView(DONE, objectStore.presignedUrl(objectStore.bucket(), stamp.getStampedObjectKey()), null);
        }
        boolean gaveUp = FAILED.equals(stamp.getStatus()) && stamp.getAttempts() >= MAX_ATTEMPTS;
        return gaveUp ? new StampView(FAILED, null, stamp.getError()) : new StampView(PENDING, null, null);
    }

    static String stampedKey(UploadStamp stamp) {
        return "uploads/%s/%s-stamped.mp4".formatted(stamp.getTenantId(), stamp.getId());
    }

    private static String truncate(String message) {
        return message.length() <= 500 ? message : message.substring(0, 500);
    }
}
