package com.dalai.llama.postprod.service.clip;

import com.dalai.llama.postprod.domain.entity.FilmRender;
import com.dalai.llama.postprod.domain.entity.FilmRenderStatus;
import com.dalai.llama.postprod.kafka.FilmUpscaleRequestedEvent;
import com.dalai.llama.postprod.kafka.FilmUpscaleRequestedPublisher;
import com.dalai.llama.postprod.repository.FilmRenderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The joined film upscaled by Topaz to full HD, as a NEW film render beside the one it came from.
 *
 * <p>Shots are generated small (480x854 is typical); the film is joined at that size. Upscaling the
 * whole film once, after the edit is settled, costs a fraction of upscaling every shot and every
 * re-take. The result becomes the project's latest film, so publishing it to the review page, the
 * Dalaillama stamp and the profile all use it; the original stays and publishing can move back.
 *
 * <p>Queued: {@link #request} records and publishes, the consumer calls {@link #upscale}. Topaz runs
 * for minutes, never on a request thread.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FilmUpscaleService {

    /** Upscaled until the short side reaches this: 1080x1920 for a vertical film. */
    static final int TARGET_SHORT_SIDE = 1080;
    static final double MAX_FACTOR = 4;

    private final FilmRenderRepository renders;
    private final ClipObjectStore objectStore;
    private final TopazEnhancer topaz;
    private final FfmpegClipProcessor ffmpeg;
    private final ShotClipVersionService clipVersions;
    private final FilmUpscaleRequestedPublisher publisher;

    @Transactional
    public FilmRender request(UUID tenantId, UUID projectId, UUID userId, UUID sourceRenderId) {
        FilmRender source = renders.findById(sourceRenderId)
                .filter(r -> tenantId.equals(r.getTenantId()) && projectId.equals(r.getProjectId()))
                .orElseThrow(() -> new ClipProcessingException("No such film"));
        if (source.getStatus() != FilmRenderStatus.COMPLETED || !source.isPlayable()) {
            throw new ClipProcessingException("The film isn't finished yet");
        }
        if (source.getUpscaledFromRenderId() != null) {
            throw new ClipProcessingException("This film is already upscaled");
        }
        BigDecimal factor = factor(source.getWidth(), source.getHeight());
        FilmRender render = renders.save(FilmRender.builder()
                .renderId(UUID.randomUUID())
                .tenantId(tenantId)
                .projectId(projectId)
                .status(FilmRenderStatus.QUEUED)
                .shotCount(source.getShotCount())
                .sourceVersionIds(source.getSourceVersionIds())
                .upscaledFromRenderId(source.getRenderId())
                .upscaleFactor(factor)
                .createdBy(userId)
                .createdAt(OffsetDateTime.now())
                .build());
        // After commit, for the reason FilmAssemblyService.request gives: before it, the consumer
        // can look the render up on its own connection and not find it.
        UUID renderId = render.getRenderId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    publisher.publish(new FilmUpscaleRequestedEvent(renderId, tenantId.toString(), projectId));
                    log.info("Queued a film upscale renderId={} from={} factor={}", renderId, sourceRenderId, factor);
                } catch (RuntimeException ex) {
                    log.warn("Could not queue the film upscale renderId={}: {}", renderId, ex.getMessage());
                    fail(renderId, "Could not queue the upscale: " + ex.getMessage());
                }
            }
        });
        return render;
    }

    /** The consumer's work. Failures are recorded on the render, never thrown back into Kafka. */
    public void upscale(UUID renderId) {
        FilmRender render = renders.findById(renderId).orElse(null);
        if (render == null || render.getStatus().isTerminal()) {
            log.info("Skipping film upscale renderId={} -- unknown or already finished", renderId);
            return;
        }
        FilmRender source = renders.findById(render.getUpscaledFromRenderId()).orElse(null);
        if (source == null || !source.isPlayable()) {
            fail(renderId, "The film it was made from is gone");
            return;
        }
        markProcessing(renderId);
        Path workDir = ffmpeg.createWorkDir("film-upscale-" + renderId);
        try {
            double seconds = source.getDurationSeconds() == null ? 0 : source.getDurationSeconds().doubleValue();
            String upscaledUrl = topaz.upscale(render.getTenantId(), render.getProjectId(),
                    objectStore.presignedUrl(source.getBucket(), source.getObjectKey()),
                    render.getUpscaleFactor().doubleValue(), seconds, "film-upscale-" + renderId);
            Path upscaled = workDir.resolve("upscaled.mp4");
            clipVersions.fetch(upscaledUrl, upscaled);
            // The film's own soundtrack, untouched: the upscale is about the picture.
            Path original = workDir.resolve("original.mp4");
            objectStore.download(source.getBucket(), source.getObjectKey(), original);
            Path output = workDir.resolve("film.mp4");
            ffmpeg.keepAudioFrom(upscaled, original, output);
            ClipProbe probe = ffmpeg.probe(output);
            if (!probe.isPlayable()) throw new ClipProcessingException("The upscaled film is not playable video");
            String objectKey = "films/%s/%s.mp4".formatted(render.getProjectId(), renderId);
            objectStore.upload(objectKey, output);
            complete(renderId, objectKey, probe);
            log.info("Upscaled film renderId={} to {}x{}", renderId, probe.width(), probe.height());
        } catch (RuntimeException ex) {
            log.warn("Film upscale failed renderId={}: {}", renderId, ex.getMessage());
            fail(renderId, ex.getMessage());
        } finally {
            ffmpeg.deleteQuietly(workDir);
        }
    }

    /** How much larger to make it: until the short side is full HD, at most 4x. Refuses a film already there. */
    static BigDecimal factor(Integer width, Integer height) {
        if (width == null || height == null || width <= 0 || height <= 0) {
            throw new ClipProcessingException("The film's size is unknown, so it can't be upscaled");
        }
        int shortSide = Math.min(width, height);
        if (shortSide >= TARGET_SHORT_SIDE) throw new ClipProcessingException("This film is already full HD");
        return BigDecimal.valueOf(Math.min(MAX_FACTOR, (double) TARGET_SHORT_SIDE / shortSide)).setScale(2, RoundingMode.UP);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markProcessing(UUID renderId) {
        renders.findById(renderId).ifPresent(r -> {
            r.setStatus(FilmRenderStatus.PROCESSING);
            renders.save(r);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(UUID renderId, String objectKey, ClipProbe probe) {
        renders.findById(renderId).ifPresent(r -> {
            r.setStatus(FilmRenderStatus.COMPLETED);
            r.setBucket(objectStore.bucket());
            r.setObjectKey(objectKey);
            r.setWidth(probe.width());
            r.setHeight(probe.height());
            r.setDurationSeconds(probe.durationSeconds());
            r.setCompletedAt(OffsetDateTime.now());
            renders.save(r);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(UUID renderId, String reason) {
        renders.findById(renderId).ifPresent(r -> {
            r.setStatus(FilmRenderStatus.FAILED);
            r.setLastError(reason);
            r.setCompletedAt(OffsetDateTime.now());
            renders.save(r);
        });
    }
}
