package com.dalai.llama.postprod.service.clip;

import com.dalai.llama.postprod.domain.ClipOrigin;
import com.dalai.llama.postprod.domain.ClipVersionStatus;
import com.dalai.llama.postprod.domain.entity.ShotClipVersion;
import com.dalai.llama.postprod.config.ClipVersionCacheConfig;
import com.dalai.llama.postprod.repository.ShotClipVersionRepository;
import com.dalai.llama.postprod.service.videogen.VideoGenerationClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Making, previewing and accepting cuts of a shot.
 *
 * <p>Every cut is made the same way and the differences between them are one step wide, so the whole
 * class is one template with three fillings: fetch what the cut is made from, run one ffmpeg filter,
 * store the result as a new numbered PREVIEW. Nothing becomes the shot's clip until someone accepts
 * it, which is the change that matters -- a cut used to replace the clip the instant it was
 * produced, so the only way to find out whether it was any good was to lose the alternative.
 *
 * <p>Version numbers are per shot and start at 1 for the generated clip. That baseline is imported
 * on first use rather than at generation time: post-production does not own generating, so the first
 * time a shot is cut here is the first time this service has any reason to know it exists.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShotClipVersionService {

    private final ShotClipVersionRepository repository;
    private final VideoGenerationClient videoGenerationClient;
    private final FfmpegClipProcessor ffmpeg;
    private final ClipObjectStore objectStore;

    /** Every cut of a shot, newest first, each with a URL that plays so it can be judged before it
     * is chosen. */
    @Cacheable(cacheNames = ClipVersionCacheConfig.SHOT_CLIP_VERSIONS, key = "#shotId")
    @Transactional(readOnly = true)
    public List<ShotClipVersion> list(UUID tenantId, UUID shotId) {
        return repository.findByShotIdOrderByVersionNumberDesc(shotId).stream()
                .filter(version -> tenantId.equals(version.getTenantId()))
                .toList();
    }

    /** The cut the film currently uses, if this service has been asked about this shot before. */
    @Transactional(readOnly = true)
    public Optional<ShotClipVersion> active(UUID shotId) {
        // Deliberately not cached: Optional does not round-trip through a JSON cache cleanly, and
        // this is a single indexed row lookup. The cached reads are the ones that assemble a list.
        return repository.findByShotIdAndStatus(shotId, ClipVersionStatus.ACTIVE);
    }

    /**
     * Makes sure this shot has version 1 -- the clip as generated -- importing it if it does not.
     *
     * <p>Versions are created lazily, the first time a shot is cut. That is right for cutting and
     * wrong for everything that asks "does this shot have a video": a project with thirteen finished
     * shots and no cuts has no version rows at all, so assembling reported every shot missing when
     * every shot was generated. Anything that needs the shot's current video calls this first.
     */
    @Caching(evict = {
            @CacheEvict(cacheNames = ClipVersionCacheConfig.SHOT_CLIP_VERSIONS, key = "#context.shotId()"),
            @CacheEvict(cacheNames = ClipVersionCacheConfig.PROJECT_ACTIVE_CLIPS, key = "#context.projectId()")
    })
    @Transactional
    public ShotClipVersion importGeneratedBaseline(Context context) {
        repository.lockByShotId(context.shotId());
        ensureBaseline(context, clipSource(context));
        return repository.findByShotIdAndStatus(context.shotId(), ClipVersionStatus.ACTIVE)
                .orElseThrow(() -> new ClipProcessingException(
                        "This shot has no video yet, so there is nothing to put in the film"));
    }

    /** A new cut carrying the recorded take in place of whatever the clip came with. */
    @Caching(evict = {
            @CacheEvict(cacheNames = ClipVersionCacheConfig.SHOT_CLIP_VERSIONS, key = "#context.shotId()"),
            @CacheEvict(cacheNames = ClipVersionCacheConfig.PROJECT_ACTIVE_CLIPS, key = "#context.projectId()")
    })
    @Transactional
    public ShotClipVersion createDubbedPreview(Context context) {
        ShotClipSource source = clipSource(context);
        if (!source.hasDub()) {
            throw new ClipProcessingException(
                    "Nothing has been dubbed for this shot yet, so there is no voice to put on it");
        }
        return cut(context, source, ClipOrigin.DUBBED, (work, clip, output) -> {
            Path audio = work.resolve("take.mp3");
            fetch(source.dubbedAudioUrl(), audio);
            ffmpeg.replaceAudio(clip, audio, output);
        });
    }

    /** A new cut with no voice at all. */
    @Caching(evict = {
            @CacheEvict(cacheNames = ClipVersionCacheConfig.SHOT_CLIP_VERSIONS, key = "#context.shotId()"),
            @CacheEvict(cacheNames = ClipVersionCacheConfig.PROJECT_ACTIVE_CLIPS, key = "#context.projectId()")
    })
    @Transactional
    public ShotClipVersion createSilentPreview(Context context) {
        ShotClipSource source = clipSource(context);
        return cut(context, source, ClipOrigin.SILENT, (work, clip, output) -> ffmpeg.stripAudio(clip, output));
    }

    /** The creator's own cut, brought back after editing it elsewhere. {@code editedFromVersionId}
     * links it to the cut it was made from, so a version that went out and came back reads as a
     * chain rather than two unrelated rows. */
    @Caching(evict = {
            @CacheEvict(cacheNames = ClipVersionCacheConfig.SHOT_CLIP_VERSIONS, key = "#context.shotId()"),
            @CacheEvict(cacheNames = ClipVersionCacheConfig.PROJECT_ACTIVE_CLIPS, key = "#context.projectId()")
    })
    @Transactional
    public ShotClipVersion createUploadedPreview(Context context, MultipartFile file, UUID editedFromVersionId) {
        if (file == null || file.isEmpty()) {
            throw new ClipProcessingException("No file was uploaded");
        }
        ShotClipSource source = clipSource(context);
        ShotClipVersion made = cut(context, source, ClipOrigin.UPLOADED, (work, clip, output) -> {
            try {
                file.transferTo(output);
            } catch (Exception ex) {
                throw new ClipProcessingException("Could not read the uploaded file: " + ex.getMessage(), ex);
            }
        });
        if (editedFromVersionId != null) {
            made.setEditedFromVersionId(editedFromVersionId);
            // The cut it was made from is back: it is no longer out being edited.
            repository.findById(editedFromVersionId).ifPresent(source1 -> {
                source1.setDownloadedForEditAt(null);
                source1.setDownloadedForEditBy(null);
                repository.save(source1);
            });
            return repository.save(made);
        }
        return made;
    }

    /**
     * Makes this cut the one the film uses, and keeps the one it replaces.
     *
     * <p>Rows are locked first. Both halves of this read then write -- which row is ACTIVE, and what
     * the next version number is -- and two requests interleaving there leave a shot with two
     * current cuts. The unique index would reject the second write, but a constraint violation is a
     * worse answer than waiting a moment and reading the truth.
     */
    @Caching(evict = {
            @CacheEvict(cacheNames = ClipVersionCacheConfig.SHOT_CLIP_VERSIONS, key = "#shotId"),
            // The project's current cuts change too, and that cache is keyed by project -- which
            // this method does not receive. Cleared wholesale rather than left stale: a film
            // assembled from a cut the creator replaced is the failure this whole flow prevents.
            @CacheEvict(cacheNames = ClipVersionCacheConfig.PROJECT_ACTIVE_CLIPS, allEntries = true)
    })
    @Transactional
    public ShotClipVersion accept(UUID tenantId, UUID shotId, UUID versionId) {
        repository.lockByShotId(shotId);
        ShotClipVersion chosen = repository.findById(versionId)
                .filter(version -> tenantId.equals(version.getTenantId()) && shotId.equals(version.getShotId()))
                .orElseThrow(() -> new ClipProcessingException("No such cut of this shot"));
        if (chosen.getStatus() == ClipVersionStatus.ACTIVE) {
            return chosen;
        }
        repository.findByShotIdAndStatus(shotId, ClipVersionStatus.ACTIVE).ifPresent(current -> {
            current.setStatus(ClipVersionStatus.SUPERSEDED);
            repository.save(current);
        });
        chosen.setStatus(ClipVersionStatus.ACTIVE);
        chosen.setAcceptedAt(OffsetDateTime.now());
        log.info("Accepted a cut shotId={} version={} origin={}",
                shotId, chosen.getVersionNumber(), chosen.getOrigin());
        return repository.save(chosen);
    }

    /**
     * Records that a cut has been taken away to be edited, and hands back the URL to fetch it from.
     *
     * <p>The marking is the point. Downloading used to leave no trace at all, so the only shots a
     * creator could account for were the ones already back -- and one taken away days ago looked
     * exactly like one nobody had touched.
     */
    @Caching(evict = {
            @CacheEvict(cacheNames = ClipVersionCacheConfig.SHOT_CLIP_VERSIONS, key = "#shotId"),
            @CacheEvict(cacheNames = ClipVersionCacheConfig.PROJECT_ACTIVE_CLIPS, allEntries = true)
    })
    @Transactional
    public ShotClipVersion markDownloadedForEdit(UUID tenantId, UUID shotId, UUID versionId, UUID userId) {
        ShotClipVersion version = repository.findById(versionId)
                .filter(candidate -> tenantId.equals(candidate.getTenantId())
                        && shotId.equals(candidate.getShotId()))
                .orElseThrow(() -> new ClipProcessingException("No such cut of this shot"));
        version.setDownloadedForEditAt(OffsetDateTime.now());
        version.setDownloadedForEditBy(userId);
        log.info("Cut taken away to edit shotId={} version={}", shotId, version.getVersionNumber());
        return repository.save(version);
    }

    /**
     * Shows this cut to the client on its own, or takes it back down.
     *
     * <p>Separate from the film because the question is different: a creator often wants one shot in
     * front of a client -- the one they are unsure about -- long before a film exists, and waiting
     * for every shot to be generated to ask one question is the wrong shape.
     */
    @Caching(evict = {
            @CacheEvict(cacheNames = ClipVersionCacheConfig.SHOT_CLIP_VERSIONS, key = "#shotId"),
            @CacheEvict(cacheNames = ClipVersionCacheConfig.PROJECT_ACTIVE_CLIPS, allEntries = true)
    })
    @Transactional
    public ShotClipVersion setPublished(UUID tenantId, UUID shotId, UUID versionId, boolean published) {
        ShotClipVersion version = repository.findById(versionId)
                .filter(candidate -> tenantId.equals(candidate.getTenantId())
                        && shotId.equals(candidate.getShotId()))
                .orElseThrow(() -> new ClipProcessingException("No such cut of this shot"));
        version.setPublished(published);
        version.setPublishedAt(published ? OffsetDateTime.now() : null);
        log.info("{} a shot shotId={} version={}",
                published ? "Published" : "Unpublished", shotId, version.getVersionNumber());
        return repository.save(version);
    }

    /** Every shot in this project the creator has chosen to show a client, newest cut per shot. */
    @Transactional(readOnly = true)
    public List<ShotClipVersion> publishedForProject(UUID tenantId, UUID projectId) {
        return repository.findByProjectIdAndPublishedIsTrue(projectId).stream()
                .filter(version -> tenantId.equals(version.getTenantId()))
                .toList();
    }

    /** The current cut of every shot in this project, in one query -- what the editor lists. */
    @Cacheable(cacheNames = ClipVersionCacheConfig.PROJECT_ACTIVE_CLIPS, key = "#projectId")
    @Transactional(readOnly = true)
    public List<ShotClipVersion> activeForProject(UUID tenantId, UUID projectId) {
        return repository.findByProjectIdAndStatus(projectId, ClipVersionStatus.ACTIVE).stream()
                .filter(version -> tenantId.equals(version.getTenantId()))
                .toList();
    }

    public String playableUrl(ShotClipVersion version) {
        return objectStore.presignedUrl(version.getBucket(), version.getObjectKey());
    }

    /** What one ffmpeg filter does to produce a cut, so the fetch/probe/store around it is written
     * once rather than three times. */
    @FunctionalInterface
    private interface CutStep {
        void apply(Path workDir, Path clip, Path output);
    }

    private ShotClipVersion cut(Context context, ShotClipSource source, ClipOrigin origin, CutStep step) {
        // Locked before the version number is chosen, so two cuts started at once do not both
        // believe they are version 4.
        repository.lockByShotId(context.shotId());
        ensureBaseline(context, source);

        Path workDir = ffmpeg.createWorkDir(context.shotId().toString());
        try {
            Path clip = workDir.resolve("current.mp4");
            ShotClipVersion current = repository.findByShotIdAndStatus(context.shotId(), ClipVersionStatus.ACTIVE)
                    .orElseThrow(() -> new ClipProcessingException(
                            "This shot has no clip yet, so there is nothing to cut from"));
            objectStore.download(current.getBucket(), current.getObjectKey(), clip);

            Path output = workDir.resolve("cut.mp4");
            step.apply(workDir, clip, output);

            ClipProbe probe = ffmpeg.probe(output);
            if (!probe.isPlayable()) {
                // Checked before anything is stored. An unplayable cut used to be uploaded and
                // promoted exactly like a good one, which is how a finished shot lost its picture.
                throw new ClipProcessingException(
                        "That produced a file with no usable video in it -- the shot is unchanged");
            }
            int nextVersion = repository.highestVersionNumber(context.shotId()) + 1;
            String objectKey = objectStore.objectKeyFor(context.shotId(), nextVersion);
            objectStore.upload(objectKey, output);

            ShotClipVersion saved = repository.save(ShotClipVersion.builder()
                    .versionId(UUID.randomUUID())
                    .tenantId(context.tenantId())
                    .projectId(context.projectId())
                    .shotId(context.shotId())
                    .shotRef(context.shotRef())
                    .sourceJobId(source.jobId())
                    .versionNumber(nextVersion)
                    .origin(origin)
                    // A preview, not the shot's clip. Accepting is a separate, deliberate step.
                    .status(ClipVersionStatus.PREVIEW)
                    .bucket(objectStore.bucket())
                    .objectKey(objectKey)
                    .durationSeconds(probe.durationSeconds())
                    .width(probe.width())
                    .height(probe.height())
                    .hasAudio(probe.hasAudio())
                    .createdBy(context.userId())
                    .createdAt(OffsetDateTime.now())
                    .build());
            log.info("Made a cut shotId={} version={} origin={} seconds={}",
                    context.shotId(), nextVersion, origin, probe.durationSeconds());
            return saved;
        } finally {
            ffmpeg.deleteQuietly(workDir);
        }
    }

    /**
     * Records the generated clip as version 1 the first time this shot is cut.
     *
     * <p>Copied into this service's own storage rather than referenced in place. A cut has to be
     * reproducible from its versions alone, and a pointer into another service's bucket is only as
     * durable as that service's own decisions about where it keeps things.
     */
    private void ensureBaseline(Context context, ShotClipSource source) {
        if (repository.highestVersionNumber(context.shotId()) > 0) {
            return;
        }
        if (source.clipUrl() == null || source.clipUrl().isBlank()) {
            throw new ClipProcessingException("This shot has not been generated yet");
        }
        Path workDir = ffmpeg.createWorkDir("baseline-" + context.shotId());
        try {
            Path generated = workDir.resolve("generated.mp4");
            fetch(source.clipUrl(), generated);
            ClipProbe probe = ffmpeg.probe(generated);
            if (!probe.isPlayable()) {
                throw new ClipProcessingException(
                        "The generated clip for this shot could not be read as video");
            }
            String objectKey = objectStore.objectKeyFor(context.shotId(), 1);
            objectStore.upload(objectKey, generated);
            repository.save(ShotClipVersion.builder()
                    .versionId(UUID.randomUUID())
                    .tenantId(context.tenantId())
                    .projectId(context.projectId())
                    .shotId(context.shotId())
                    .shotRef(context.shotRef())
                    .sourceJobId(source.jobId())
                    .versionNumber(1)
                    .origin(ClipOrigin.GENERATED)
                    .status(ClipVersionStatus.ACTIVE)
                    .bucket(objectStore.bucket())
                    .objectKey(objectKey)
                    .durationSeconds(probe.durationSeconds())
                    .width(probe.width())
                    .height(probe.height())
                    .hasAudio(probe.hasAudio())
                    .createdBy(context.userId())
                    .createdAt(OffsetDateTime.now())
                    .build());
            log.info("Imported the generated clip as version 1 shotId={} seconds={}",
                    context.shotId(), probe.durationSeconds());
        } finally {
            ffmpeg.deleteQuietly(workDir);
        }
    }

    private ShotClipSource clipSource(Context context) {
        return videoGenerationClient.getClipSource(context.tenantId(), context.projectId(), context.shotId());
    }

    /** Fetches a presigned URL to a file, and refuses anything too small to be media -- an expired
     * link returns a short error body that lands on disk as a real file that is not a video. */
    private void fetch(String url, Path target) {
        try {
            HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
            connection.setConnectTimeout(30_000);
            connection.setReadTimeout(300_000);
            try {
                int status = connection.getResponseCode();
                if (status < 200 || status >= 300) {
                    throw new ClipProcessingException("Fetching the source returned HTTP " + status);
                }
                try (var in = connection.getInputStream()) {
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                connection.disconnect();
            }
            if (Files.size(target) < 1024) {
                throw new ClipProcessingException(
                        "The source came back too small to be media -- the link may have expired");
            }
        } catch (ClipProcessingException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ClipProcessingException("Could not fetch the source: " + ex.getMessage(), ex);
        }
    }

    /** Who and what a cut is being made for. Passed as one object because every entry point needs
     * all of it and a five-argument signature invites them being swapped. */
    public record Context(UUID tenantId, UUID projectId, UUID shotId, String shotRef, UUID userId) {
    }
}
