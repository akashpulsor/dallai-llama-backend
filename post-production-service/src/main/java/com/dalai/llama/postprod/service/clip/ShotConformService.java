package com.dalai.llama.postprod.service.clip;

import com.dalai.llama.postprod.domain.FrameExtractionStatus;
import com.dalai.llama.postprod.domain.entity.ShotClipConform;
import com.dalai.llama.postprod.domain.entity.ShotClipVersion;
import com.dalai.llama.postprod.dto.ShotConformDtos;
import com.dalai.llama.postprod.kafka.ShotConformRequestedPublisher;
import com.dalai.llama.postprod.repository.ShotClipConformRepository;
import com.dalai.llama.postprod.service.PostProductionException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Brings a shot's newest generated clip to its planned length, so a clip can be generated shorter
 * (cheaper) -- or at a model's minimum -- and still fit the film.
 *
 * <p>Shorter clips are slowed down; with interpolation, ffmpeg synthesises the in-between frames so
 * the motion stays smooth at the clip's own frame rate. Longer clips are trimmed. The dubbed line
 * and the music bed are laid on at normal speed, since the clip's own audio cannot survive being
 * slowed. The result becomes the shot's active cut; the clip as generated is kept beside it.
 *
 * <p>Queued: {@link #request} records and publishes, ShotConformConsumer calls {@link #process}
 * (one clip at a time per consumer -- interpolation is CPU work), and callers poll {@link #status}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShotConformService {

    private final ShotClipConformRepository conforms;
    private final ShotClipVersionService clipVersions;
    private final FfmpegClipProcessor ffmpeg;
    private final ShotConformRequestedPublisher publisher;

    public ShotConformDtos.View request(UUID tenantId, UUID projectId, UUID shotId, ShotConformDtos.Request request) {
        ShotClipConform row = conforms.save(ShotClipConform.builder()
                .requestId(UUID.randomUUID())
                .tenantId(tenantId)
                .projectId(projectId)
                .shotId(shotId)
                .targetSeconds(request.targetSeconds().setScale(3, RoundingMode.HALF_UP))
                .interpolate(!Boolean.FALSE.equals(request.interpolate()))
                .status(FrameExtractionStatus.QUEUED)
                .createdAt(OffsetDateTime.now())
                .build());
        try {
            publisher.publish(new ShotConformDtos.RequestedEvent(row.getRequestId(), tenantId, shotId));
        } catch (PostProductionException ex) {
            finish(row, FrameExtractionStatus.FAILED, null, ex.getMessage());
            throw ex;
        }
        return view(row);
    }

    public ShotConformDtos.View status(UUID tenantId, UUID requestId) {
        return conforms.findByRequestIdAndTenantId(requestId, tenantId).map(ShotConformService::view)
                .orElseThrow(() -> PostProductionException.notFound("No conform request " + requestId));
    }

    /** The consumer's work. Failures are recorded on the request, never thrown back into Kafka. */
    public void process(UUID requestId) {
        Optional<ShotClipConform> found = conforms.findById(requestId);
        if (found.isEmpty() || found.get().getStatus().isFinished()) {
            log.info("Skipping conform requestId={} -- unknown or already finished", requestId);
            return;
        }
        ShotClipConform row = found.get();
        row.setStatus(FrameExtractionStatus.PROCESSING);
        conforms.save(row);

        ShotClipVersionService.Context context =
                new ShotClipVersionService.Context(row.getTenantId(), row.getProjectId(), row.getShotId(), null, null);
        Path workDir = ffmpeg.createWorkDir("conform-" + row.getShotId());
        try {
            ShotClipSource source = clipVersions.clipSource(context);
            if (source.clipUrl() == null || source.clipUrl().isBlank()) {
                throw new ClipProcessingException("This shot has no generated clip yet");
            }
            row.setSourceJobId(source.jobId());
            Path generated = workDir.resolve("generated.mp4");
            clipVersions.fetch(source.clipUrl(), generated);
            ClipProbe generatedProbe = playable(ffmpeg.probe(generated), "The generated clip");

            Path dub = source.hasDub() ? fetched(source.dubbedAudioUrl(), workDir.resolve("take.mp3")) : null;
            // No music bed: shot music is a sound layer, mixed into the film when it renders.
            Path music = null;

            Path output = workDir.resolve("conformed.mp4");
            ffmpeg.conform(generated, dub, music, row.getTargetSeconds().doubleValue(), row.getInterpolate(),
                    ffmpeg.frameRate(generated), output);
            ClipProbe conformedProbe = playable(ffmpeg.probe(output), "The conformed clip");

            ShotClipVersion cut = clipVersions.storeConformed(context, source.jobId(), generated, generatedProbe, output, conformedProbe);
            finish(row, FrameExtractionStatus.COMPLETED, cut.getVersionId(), null);
            log.info("Conformed shotId={} from {}s to {}s interpolate={} version={}", row.getShotId(),
                    generatedProbe.durationSeconds(), row.getTargetSeconds(), row.getInterpolate(), cut.getVersionNumber());
        } catch (RuntimeException ex) {
            log.warn("Conform failed requestId={} shotId={}: {}", requestId, row.getShotId(), ex.getMessage());
            finish(row, FrameExtractionStatus.FAILED, null, ex.getMessage());
        } finally {
            ffmpeg.deleteQuietly(workDir);
        }
    }

    private Path fetched(String url, Path target) {
        clipVersions.fetch(url, target);
        return target;
    }

    private static ClipProbe playable(ClipProbe probe, String what) {
        if (!probe.isPlayable()) {
            throw new ClipProcessingException(what + " is not playable video");
        }
        return probe;
    }

    private void finish(ShotClipConform row, FrameExtractionStatus status, UUID versionId, String error) {
        row.setStatus(status);
        row.setVersionId(versionId);
        row.setError(error);
        row.setCompletedAt(OffsetDateTime.now());
        conforms.save(row);
    }

    private static ShotConformDtos.View view(ShotClipConform row) {
        BigDecimal target = row.getTargetSeconds();
        return new ShotConformDtos.View(row.getRequestId(), row.getShotId(), target, Boolean.TRUE.equals(row.getInterpolate()),
                row.getStatus().name(), row.getSourceJobId(), row.getVersionId(), row.getError());
    }
}
