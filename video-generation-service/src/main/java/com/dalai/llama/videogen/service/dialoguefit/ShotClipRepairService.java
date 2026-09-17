package com.dalai.llama.videogen.service.dialoguefit;

import com.dalai.llama.videogen.domain.JobStatus;
import com.dalai.llama.videogen.domain.entity.VideoGenJob;
import com.dalai.llama.videogen.repository.ShotPromptRepository;
import com.dalai.llama.videogen.repository.VideoGenJobRepository;
import com.dalai.llama.videogen.service.CloneAudioService;
import com.dalai.llama.videogen.service.VideoAssetPersistenceService;
import com.dalai.llama.videogen.service.VideoGenException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Repairs a finished clip instead of paying to generate it again.
 *
 * <p>A shot whose dialogue overruns has, until now, only expensive answers: render the whole thing
 * again at a longer duration, or cut the line. Both throw away a clip that was paid for and
 * accepted. These two repairs keep it.
 *
 * <ul>
 *   <li>{@link #extendTail} adds only the missing seconds -- held from the last frame for nothing, or
 *       animated on from it by a cheaper model -- and lays the whole dialogue across the join.</li>
 *   <li>{@link #uploadClip} takes a finished file from the creator. When neither automatic repair
 *       gives something worth shipping, they can pull the clip and the audio down, fix it in the
 *       tool they already know, and put the result back. It is the escape hatch that stops any of
 *       this being a dead end.</li>
 * </ul>
 *
 * <p>Both replace the job's output and record {@code output_origin}, because a repaired clip is
 * otherwise indistinguishable from a generated one and a later re-render would discard hand-finished
 * work with nobody able to see what was lost.
 */
@Slf4j
@Service
public class ShotClipRepairService {

    /** The breath left after the last word, matching {@code video-gen.dialogue-fit.tail-seconds}. */
    private static final double TAIL_SECONDS = 0.4;

    private final VideoGenJobRepository videoGenJobRepository;
    private final ShotPromptRepository shotPromptRepository;
    private final VideoAssetPersistenceService assetPersistenceService;
    private final CloneAudioService cloneAudioService;
    private final ClipTailExtensionService tailExtensionService;
    private final AudioDurationProbe durationProbe;
    /** Where MinioVideoAssetPersistenceService puts a generated clip. Derived the same way it
     * derives it, so the two cannot drift into disagreeing about where the clip lives. */
    private final String clipBucket;
    private final String clipPrefix;

    public ShotClipRepairService(VideoGenJobRepository videoGenJobRepository,
                                 ShotPromptRepository shotPromptRepository,
                                 VideoAssetPersistenceService assetPersistenceService,
                                 CloneAudioService cloneAudioService,
                                 ClipTailExtensionService tailExtensionService,
                                 AudioDurationProbe durationProbe,
                                 @org.springframework.beans.factory.annotation.Value("${video-gen.minio.bucket}")
                                 String clipBucket,
                                 @org.springframework.beans.factory.annotation.Value("${video-gen.minio.export-prefix}")
                                 String exportPrefix) {
        this.videoGenJobRepository = videoGenJobRepository;
        this.shotPromptRepository = shotPromptRepository;
        this.assetPersistenceService = assetPersistenceService;
        this.cloneAudioService = cloneAudioService;
        this.tailExtensionService = tailExtensionService;
        this.durationProbe = durationProbe;
        this.clipBucket = clipBucket;
        this.clipPrefix = exportPrefix.replaceAll("-exports$", "") + "-clips";
    }

    /** What the creator needs to repair a shot by hand: the clip as generated and the dialogue take
     * that does not fit it, both as URLs a browser can simply download. */
    public RepairSources sources(UUID tenantId, UUID projectId, UUID shotId) {
        VideoGenJob job = latestCompletedJob(tenantId, shotId);
        String clipUrl = job.getOutputBucket() == null ? job.getOutputUri()
                : assetPersistenceService.presignedUrl(job.getOutputBucket(), job.getOutputObjectKey());
        String audioUrl = dubbedAudioUrl(tenantId, projectId, shotId);
        double clipSeconds = durationProbe.probeUrl(clipUrl);
        double audioSeconds = durationProbe.probeUrl(audioUrl);
        return new RepairSources(job.getJobId(), clipUrl, audioUrl,
                clipSeconds > 0 ? clipSeconds : null, audioSeconds > 0 ? audioSeconds : null,
                job.getOutputOrigin());
    }

    /**
     * @param tailSeconds how much to add. Null asks for exactly what the audio needs -- the measured
     *                    dialogue minus the clip it has to fit in, rounded up to a whole second.
     */
    public RepairResult extendTail(UUID tenantId, UUID projectId, UUID shotId, Integer tailSeconds,
                                   ClipTailExtensionService.Mode mode, String continuationPrompt) {
        VideoGenJob job = latestCompletedJob(tenantId, shotId);
        String clipUrl = job.getOutputBucket() == null ? job.getOutputUri()
                : assetPersistenceService.presignedUrl(job.getOutputBucket(), job.getOutputObjectKey());
        String audioUrl = dubbedAudioUrl(tenantId, projectId, shotId);
        // Silencing needs no dialogue -- it is precisely the repair for a shot that has none and was
        // given a voice anyway.
        if (audioUrl == null && mode != ClipTailExtensionService.Mode.SILENCE) {
            throw VideoGenException.badRequest(
                    "This shot has no dubbed dialogue to extend for -- dub it first");
        }

        // Replacing the audio adds no picture, so it has no tail to size and no shortfall to
        // require. It is the right repair precisely when the dub DOES fit and the clip simply has
        // the wrong sound on it.
        int seconds = 0;
        if (mode != ClipTailExtensionService.Mode.REPLACE_AUDIO
                && mode != ClipTailExtensionService.Mode.SILENCE) {
            seconds = tailSeconds != null ? tailSeconds : neededTailSeconds(clipUrl, audioUrl);
            if (seconds <= 0) {
                throw VideoGenException.badRequest(
                        "The dialogue already fits this clip -- use Replace audio instead of extending");
            }
        }

        ClipTailExtensionService.Extended extended = tailExtensionService.extend(
                tenantId, projectId, job.getJobId(), clipUrl, audioUrl, seconds, mode, continuationPrompt,
                // Taken from the job, which is what the shot was actually dispatched at -- not from
                // the shot's current plan, which may have been edited since it was generated.
                job.getResolution(), job.getAspectRatio());

        job.setOutputBucket(extended.bucket());
        job.setOutputObjectKey(extended.objectKey());
        job.setOutputUri(extended.url());
        job.setOutputOrigin(originFor(extended.mode()));
        job.setDurationSeconds((int) Math.ceil(extended.finalSeconds()));
        videoGenJobRepository.save(job);
        log.info("Repaired a clip by extending its tail jobId={} shotId={} mode={} added={}s",
                job.getJobId(), shotId, extended.mode(), extended.addedSeconds());
        return new RepairResult(job.getJobId(), extended.url(), extended.finalSeconds(), job.getOutputOrigin());
    }

    /**
     * Points the shot back at the clip that was generated for it.
     *
     * <p>A repair replaces the job's only pointer to its clip. When a repair goes wrong -- and one
     * did, leaving a finished shot with no video at all -- the generated clip is not gone: it is
     * still in MinIO, because every repair writes a NEW object rather than overwriting, and the
     * generated one is stored under a key derived from the job id. That determinism is what makes
     * this recoverable without having recorded anything in advance.
     *
     * <p>Refuses rather than guesses. If the object is not there or will not probe as video, the
     * job is left exactly as it is: a shot showing a broken repair is worse than a shot showing
     * nothing, but a shot pointed at an object that does not exist is worse than both.
     */
    public RepairResult restoreGenerated(UUID tenantId, UUID projectId, UUID shotId) {
        VideoGenJob job = latestCompletedJob(tenantId, shotId);
        String objectKey = "%s/%s.mp4".formatted(clipPrefix, job.getJobId());
        String url = assetPersistenceService.presignedUrl(clipBucket, objectKey);
        double seconds = durationProbe.probeUrl(url);
        if (seconds <= 0) {
            throw VideoGenException.notFound(
                    "The originally generated clip for this shot is no longer in storage, so there is"
                    + " nothing to restore -- generate the shot again");
        }
        job.setOutputBucket(clipBucket);
        job.setOutputObjectKey(objectKey);
        job.setOutputUri(url);
        job.setOutputOrigin("GENERATED");
        job.setDurationSeconds((int) Math.ceil(seconds));
        videoGenJobRepository.save(job);
        log.info("Restored a shot to its generated clip jobId={} shotId={} length={}s",
                job.getJobId(), shotId, seconds);
        return new RepairResult(job.getJobId(), url, seconds, job.getOutputOrigin());
    }

    /** The creator's own finished clip, replacing whatever the model produced. */
    public RepairResult uploadClip(UUID tenantId, UUID projectId, UUID shotId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw VideoGenException.badRequest("No file was uploaded");
        }
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(java.util.Locale.ROOT);
        String type = file.getContentType() == null ? "" : file.getContentType().toLowerCase(java.util.Locale.ROOT);
        if (!type.startsWith("video/") && !name.endsWith(".mp4") && !name.endsWith(".mov") && !name.endsWith(".webm")) {
            throw VideoGenException.badRequest("That is not a video file");
        }
        VideoGenJob job = latestCompletedJob(tenantId, shotId);
        Path temp = null;
        try {
            temp = Files.createTempFile("upload-" + shotId, ".mp4");
            file.transferTo(temp);
            // Probed before it is accepted: a file that ffprobe cannot read is one the final render
            // would fail on later, and failing here names the shot.
            double seconds = durationProbe.probeFile(temp);
            if (seconds <= 0) {
                throw VideoGenException.badRequest("That file could not be read as a video");
            }
            VideoAssetPersistenceService.PersistedAsset asset = assetPersistenceService.uploadFile(
                    job.getOutputBucket() == null ? "creator-assets" : job.getOutputBucket(),
                    "uploaded-clips/%s-%s.mp4".formatted(job.getJobId(), UUID.randomUUID()), temp);
            job.setOutputBucket(asset.bucket());
            job.setOutputObjectKey(asset.objectKey());
            job.setOutputUri(assetPersistenceService.presignedUrl(asset.bucket(), asset.objectKey()));
            job.setOutputOrigin("UPLOADED");
            job.setDurationSeconds((int) Math.ceil(seconds));
            videoGenJobRepository.save(job);
            log.info("Creator supplied their own clip jobId={} shotId={} seconds={}", job.getJobId(), shotId, seconds);
            return new RepairResult(job.getJobId(), job.getOutputUri(), seconds, job.getOutputOrigin());
        } catch (VideoGenException ex) {
            throw ex;
        } catch (Exception ex) {
            throw VideoGenException.upstream("Could not store the uploaded clip: " + ex.getMessage(), ex);
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (Exception ignored) {
                    // Temp file; not worth failing an accepted upload over.
                }
            }
        }
    }

    /** What produced this clip, said plainly. "TAIL_" was prefixed onto every mode, which made
     * TAIL_REPLACE_AUDIO -- eighteen characters into a sixteen-character column -- and described
     * neither of the two repairs that add no tail at all. */
    private static String originFor(String mode) {
        return switch (mode) {
            case "HOLD" -> "TAIL_FROZEN";
            case "GENERATE" -> "TAIL_GENERATED";
            case "REPLACE_AUDIO" -> "DUBBED";
            case "SILENCE" -> "SILENCED";
            default -> "REPAIRED";
        };
    }

    /** Rounded up to a whole second, with the breath after the last word already inside the measured
     * take. Zero when the dialogue already fits. */
    private int neededTailSeconds(String clipUrl, String audioUrl) {
        double clip = durationProbe.probeUrl(clipUrl);
        double audio = durationProbe.probeUrl(audioUrl);
        if (clip <= 0 || audio <= 0) {
            throw VideoGenException.upstream("Could not measure the clip or the dialogue to size a tail");
        }
        // Plus the breath after the last word -- the same tail the fit maths reserves everywhere
        // else. Without it a 6s line on a 2s clip asks for 4 more seconds and ends exactly on the
        // final frame, with the last word clipped by the cut.
        return (int) Math.max(0, Math.ceil(audio + TAIL_SECONDS - clip));
    }

    private String dubbedAudioUrl(UUID tenantId, UUID projectId, UUID shotId) {
        List<CloneAudioService.CloneAudioView> takes = cloneAudioService.list(tenantId, projectId);
        return takes.stream()
                .filter(t -> shotId.equals(t.shotId()) && t.audioUrl() != null)
                // Longest take wins when a shot has several: it is the one that decides whether the
                // clip is long enough, so sizing a tail from a shorter one would still cut.
                .max(Comparator.comparing(t -> t.durationMs() == null ? 0 : t.durationMs()))
                .map(CloneAudioService.CloneAudioView::audioUrl)
                .orElse(null);
    }

    private VideoGenJob latestCompletedJob(UUID tenantId, UUID shotId) {
        List<UUID> jobIds = shotPromptRepository.findByShotIdOrderByCreatedAtDesc(shotId).stream()
                .map(p -> p.getJobId())
                .distinct()
                .toList();
        return jobIds.stream()
                .map(videoGenJobRepository::findById)
                .filter(java.util.Optional::isPresent)
                .map(java.util.Optional::get)
                .filter(j -> tenantId.equals(j.getTenantId()))
                .filter(j -> j.getStatus() == JobStatus.COMPLETED && j.getOutputObjectKey() != null)
                .findFirst()
                .orElseThrow(() -> VideoGenException.notFound(
                        "Shot " + shotId + " has no finished clip to repair"));
    }

    public record RepairSources(UUID jobId, String clipUrl, String audioUrl,
                                Double clipSeconds, Double audioSeconds, String outputOrigin) {}

    public record RepairResult(UUID jobId, String videoUrl, double seconds, String outputOrigin) {}
}
