package com.dalai.llama.postprod.service;

import com.dalai.llama.postprod.domain.FrameExtractionMode;
import com.dalai.llama.postprod.domain.entity.ShotClipVersion;
import com.dalai.llama.postprod.domain.entity.ShotFrame;
import com.dalai.llama.postprod.dto.ShotFrameExtractionResult;
import com.dalai.llama.postprod.dto.ShotFrameView;
import com.dalai.llama.postprod.repository.ShotFrameRepository;
import com.dalai.llama.postprod.service.clip.ClipObjectStore;
import com.dalai.llama.postprod.service.clip.ClipProbe;
import com.dalai.llama.postprod.service.clip.ClipProcessingException;
import com.dalai.llama.postprod.service.clip.FfmpegClipProcessor;
import com.dalai.llama.postprod.service.clip.ShotClipVersionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Frames taken from the cut a shot currently uses: its first or last frame, a sample, or all of
 * them.
 *
 * <p>The source is the shot's ACTIVE cut, not the clip as generated. A shot's clip is replaced
 * whenever a cut is accepted -- retimed, dubbed, uploaded -- and the next shot has to continue from
 * the frame the audience actually sees. A shot never cut has its generated clip imported as its
 * first version, the same way every other cutting operation starts.
 *
 * <p>Frames are stored per cut. Asking again for a cut already extracted in that mode returns the
 * stored frames without running ffmpeg, so taking the previous shot's last frame for several shots
 * costs one extraction.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShotFrameService {

    private final ShotClipVersionService clipVersionService;
    private final FfmpegClipProcessor ffmpeg;
    private final ClipObjectStore objectStore;
    private final ShotFrameRepository frameRepository;
    private final TransactionTemplate transactionTemplate;

    /** Every frame of a long clip is a lot of objects; past this, sample instead. */
    @Value("${post-production.frame-extraction.max-frames:900}")
    private int maxFrames;

    public ShotFrameExtractionResult extract(UUID tenantId, UUID projectId, UUID shotId,
                                             FrameExtractionMode mode, Integer sampleFps) {
        if (mode == FrameExtractionMode.SAMPLE && (sampleFps == null || sampleFps <= 0)) {
            throw PostProductionException.badRequest("SAMPLE needs sampleFps, the number of frames per second to take");
        }
        ShotClipVersion cut = currentCut(tenantId, projectId, shotId);
        List<ShotFrame> stored = frameRepository.findByTenantIdAndShotIdAndClipVersionIdAndModeOrderByTimestampMsAsc(
                tenantId, shotId, cut.getVersionId(), mode);
        // A sample may have been taken at another rate, so it is always taken again; every other
        // mode is fixed by the cut.
        if (!stored.isEmpty() && mode != FrameExtractionMode.SAMPLE) {
            return result(shotId, cut, mode, stored);
        }
        List<ShotFrame> frames = extractFromCut(tenantId, projectId, shotId, cut, mode, sampleFps);
        transactionTemplate.executeWithoutResult(status -> {
            frameRepository.deleteExtraction(shotId, cut.getVersionId(), mode);
            frameRepository.saveAll(frames);
        });
        log.info("Extracted frames shotId={} clipVersionId={} mode={} count={}",
                shotId, cut.getVersionId(), mode, frames.size());
        return result(shotId, cut, mode, frames);
    }

    /** Every frame stored for a shot, newest extraction first. */
    public List<ShotFrameView> list(UUID tenantId, UUID shotId) {
        return frameRepository.findByTenantIdAndShotIdOrderByCreatedAtDescTimestampMsAsc(tenantId, shotId).stream()
                .map(this::view)
                .toList();
    }

    private ShotClipVersion currentCut(UUID tenantId, UUID projectId, UUID shotId) {
        try {
            return clipVersionService.active(shotId)
                    .filter(version -> tenantId.equals(version.getTenantId()))
                    .orElseGet(() -> clipVersionService.importGeneratedBaseline(
                            new ShotClipVersionService.Context(tenantId, projectId, shotId, null, null)));
        } catch (ClipProcessingException ex) {
            throw PostProductionException.conflict("This shot has no video yet, so there are no frames to take: " + ex.getMessage());
        }
    }

    private List<ShotFrame> extractFromCut(UUID tenantId, UUID projectId, UUID shotId, ShotClipVersion cut,
                                           FrameExtractionMode mode, Integer sampleFps) {
        Path workDir = ffmpeg.createWorkDir("frames-" + shotId);
        try {
            Path clip = workDir.resolve("clip.mp4");
            objectStore.download(cut.getBucket(), cut.getObjectKey(), clip);
            double nativeFps = ffmpeg.frameRate(clip);
            ClipProbe probe = ffmpeg.probe(clip);
            double durationSeconds = probe.durationSeconds() == null ? 0 : probe.durationSeconds().doubleValue();

            List<Path> files = new ArrayList<>();
            List<long[]> positions = new ArrayList<>(); // {frameNumber or -1, timestampMs}
            switch (mode) {
                case FIRST_FRAME, LAST_FRAME -> {
                    boolean last = mode == FrameExtractionMode.LAST_FRAME;
                    Path output = workDir.resolve(last ? "last.jpg" : "first.jpg");
                    ffmpeg.extractFrame(clip, last, output);
                    files.add(output);
                    positions.add(last ? lastPosition(nativeFps, durationSeconds) : new long[]{0, 0});
                }
                case SAMPLE, ALL -> {
                    Path dir = workDir.resolve("frames");
                    java.nio.file.Files.createDirectories(dir);
                    files.addAll(ffmpeg.extractFrames(clip, mode == FrameExtractionMode.SAMPLE ? sampleFps : null, dir));
                    if (files.size() > maxFrames) {
                        throw PostProductionException.badRequest(
                                "That is %d frames, more than the %d allowed at once -- use SAMPLE with a lower sampleFps"
                                        .formatted(files.size(), maxFrames));
                    }
                    double rate = mode == FrameExtractionMode.SAMPLE ? sampleFps : nativeFps;
                    for (int i = 0; i < files.size(); i++) {
                        long timestampMs = rate > 0 ? Math.round(i * 1000.0 / rate) : 0;
                        long frameNumber = nativeFps > 0 ? Math.round(timestampMs / 1000.0 * nativeFps) : -1;
                        positions.add(new long[]{frameNumber, timestampMs});
                    }
                }
            }

            OffsetDateTime now = OffsetDateTime.now();
            List<ShotFrame> frames = new ArrayList<>();
            for (int i = 0; i < files.size(); i++) {
                String key = objectStore.frameKeyFor(shotId, cut.getVersionId(), mode.name(), files.get(i).getFileName().toString());
                objectStore.upload(key, files.get(i), "image/jpeg");
                frames.add(ShotFrame.builder()
                        .frameId(UUID.randomUUID())
                        .tenantId(tenantId)
                        .projectId(projectId)
                        .shotId(shotId)
                        .clipVersionId(cut.getVersionId())
                        .mode(mode)
                        .frameNumber(positions.get(i)[0] < 0 ? null : positions.get(i)[0])
                        .timestampMs(positions.get(i)[1])
                        .bucket(objectStore.bucket())
                        .objectKey(key)
                        .createdAt(now)
                        .build());
            }
            return frames;
        } catch (PostProductionException ex) {
            throw ex;
        } catch (ClipProcessingException ex) {
            throw new PostProductionException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not take frames from this clip: " + ex.getMessage(), ex);
        } catch (Exception ex) {
            throw new PostProductionException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not take frames from this clip: " + ex.getMessage(), ex);
        } finally {
            ffmpeg.deleteQuietly(workDir);
        }
    }

    /** The last frame's index and start time: one frame before the end at the clip's own rate. */
    static long[] lastPosition(double nativeFps, double durationSeconds) {
        if (nativeFps <= 0 || durationSeconds <= 0) {
            return new long[]{-1, Math.max(0, Math.round(durationSeconds * 1000))};
        }
        long frameCount = Math.round(durationSeconds * nativeFps);
        long lastFrame = Math.max(0, frameCount - 1);
        return new long[]{lastFrame, Math.round(lastFrame * 1000.0 / nativeFps)};
    }

    private ShotFrameExtractionResult result(UUID shotId, ShotClipVersion cut, FrameExtractionMode mode, List<ShotFrame> frames) {
        return new ShotFrameExtractionResult(shotId, cut.getVersionId(), mode.name(), frames.size(),
                frames.stream().map(this::view).toList());
    }

    private ShotFrameView view(ShotFrame frame) {
        return new ShotFrameView(frame.getFrameId(), frame.getShotId(), frame.getClipVersionId(), frame.getMode().name(),
                frame.getFrameNumber(), frame.getTimestampMs(), frame.getBucket(), frame.getObjectKey(),
                objectStore.presignedUrl(frame.getBucket(), frame.getObjectKey()));
    }
}
