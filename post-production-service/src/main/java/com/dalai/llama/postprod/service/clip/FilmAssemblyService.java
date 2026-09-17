package com.dalai.llama.postprod.service.clip;

import com.dalai.llama.postprod.domain.ClipVersionStatus;
import com.dalai.llama.postprod.domain.entity.FilmRender;
import com.dalai.llama.postprod.domain.entity.FilmRenderStatus;
import com.dalai.llama.postprod.domain.entity.ShotClipVersion;
import com.dalai.llama.postprod.kafka.FilmAssemblyRequestedEvent;
import com.dalai.llama.postprod.kafka.FilmAssemblyRequestedPublisher;
import com.dalai.llama.postprod.repository.FilmRenderRepository;
import com.dalai.llama.postprod.repository.ShotClipVersionRepository;
import com.dalai.llama.postprod.service.preproduction.PreProductionClient;
import com.dalai.llama.postprod.service.preproduction.PreProductionShotSummary;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Joining every shot's current cut into one film.
 *
 * <p>Ordering comes from pre-production's shot_number, not from anything this service knows. The
 * order cuts happened to be made in is not the edit, and a film assembled in that order is not the
 * film.
 *
 * <p>Refuses when any shot has no cut yet. A film missing three of its shots is not a shorter film,
 * it is the wrong one, and handing it over as though it were finished is the failure this check
 * exists to prevent. The refusal names what is missing so the page can say which shots to generate
 * rather than just disabling a button.
 *
 * <p>Every shot is padded to one size rather than cropped. Losing the edge of a frame to make a join
 * work is not a trade anyone asked for, and the project's own aspect ratio is the right shape to
 * pad to -- falling back to the first shot's dimensions when a project has no config yet, which is
 * better than forcing a guess.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FilmAssemblyService {

    /** Widths for each aspect ratio the pipeline produces, at 1080-class quality. Height follows
     * from the ratio, and both are forced even because H.264 cannot encode odd dimensions. */
    private static final Map<String, int[]> RATIO_SIZES = Map.of(
            "RATIO_16_9", new int[]{1920, 1080},
            "RATIO_9_16", new int[]{1080, 1920},
            "RATIO_1_1", new int[]{1080, 1080},
            "RATIO_4_5", new int[]{1080, 1350},
            "RATIO_21_9", new int[]{1920, 824});

    private final FilmRenderRepository filmRenderRepository;
    private final FilmAssemblyRequestedPublisher assemblyRequestedPublisher;
    private final ShotClipVersionRepository clipVersionRepository;
    private final PreProductionClient preProductionClient;
    private final FfmpegClipProcessor ffmpeg;
    private final ClipObjectStore objectStore;

    /** What the page needs to decide whether the combine button is usable, without assembling
     * anything. Cheap: two reads and no ffmpeg. */
    @Transactional(readOnly = true)
    public Readiness readiness(UUID tenantId, UUID projectId) {
        List<PreProductionShotSummary> shots = preProductionClient.listShots(tenantId, projectId);
        Map<UUID, ShotClipVersion> cuts = activeCutsByShot(projectId);
        List<String> missing = shots.stream()
                .filter(shot -> !cuts.containsKey(shot.id()))
                .map(shot -> shot.shotRef() == null ? "(unnamed)" : shot.shotRef())
                .toList();
        return new Readiness(shots.size(), shots.size() - missing.size(), missing);
    }

    /** Records the request and returns immediately. The join itself runs off the request thread. */
    @Transactional
    public FilmRender request(UUID tenantId, UUID projectId, UUID userId) {
        Readiness readiness = readiness(tenantId, projectId);
        if (readiness.total() == 0) {
            throw new ClipProcessingException("This project has no shots to join");
        }
        if (!readiness.isReady()) {
            throw new ClipProcessingException(
                    "These shots have no video yet: " + String.join(", ", readiness.missingShotRefs()));
        }
        FilmRender render = filmRenderRepository.save(FilmRender.builder()
                .renderId(UUID.randomUUID())
                .tenantId(tenantId)
                .projectId(projectId)
                .status(FilmRenderStatus.QUEUED)
                .shotCount(readiness.total())
                .createdBy(userId)
                .createdAt(OffsetDateTime.now())
                .build());
        try {
            assemblyRequestedPublisher.publish(
                    new FilmAssemblyRequestedEvent(render.getRenderId(), tenantId.toString(), projectId, userId));
        } catch (RuntimeException ex) {
            // A QUEUED row with no event behind it would sit there for ever, so the failure is
            // recorded where the page is already looking rather than left to a timeout.
            render.setStatus(FilmRenderStatus.FAILED);
            render.setLastError("Could not queue the film for joining: " + ex.getMessage());
            render.setCompletedAt(OffsetDateTime.now());
            filmRenderRepository.save(render);
            throw ex;
        }
        log.info("Queued a film renderId={} projectId={} shots={}",
                render.getRenderId(), projectId, readiness.total());
        return render;
    }

    /**
     * Does the join. Called from the worker, never from a request thread.
     *
     * <p>Each state transition commits on its own -- REQUIRES_NEW rather than one transaction around
     * the whole thing, because ffmpeg here runs for minutes and a single transaction would hold a
     * pooled connection for all of it while leaving every intermediate state invisible to the page
     * polling for it.
     */
    public void assemble(UUID renderId) {
        FilmRender render = markProcessing(renderId);
        Path workDir = ffmpeg.createWorkDir("film-" + renderId);
        try {
            List<PreProductionShotSummary> shots = preProductionClient.listShots(
                    render.getTenantId(), render.getProjectId());
            Map<UUID, ShotClipVersion> cuts = activeCutsByShot(render.getProjectId());

            List<ShotClipVersion> ordered = shots.stream()
                    .sorted(Comparator.comparing(shot ->
                            Optional.ofNullable(shot.shotNumber()).orElse(Integer.MAX_VALUE)))
                    .map(shot -> cuts.get(shot.id()))
                    .filter(java.util.Objects::nonNull)
                    .toList();
            if (ordered.size() != shots.size()) {
                throw new ClipProcessingException(
                        "Some shots lost their video while the film was being put together");
            }

            List<Path> clips = new java.util.ArrayList<>(ordered.size());
            for (int i = 0; i < ordered.size(); i++) {
                ShotClipVersion cut = ordered.get(i);
                Path clip = workDir.resolve("%03d.mp4".formatted(i + 1));
                objectStore.download(cut.getBucket(), cut.getObjectKey(), clip);
                clips.add(clip);
            }

            int[] size = targetSize(render.getTenantId(), render.getProjectId(), ordered);
            Path output = workDir.resolve("film.mp4");
            ffmpeg.concat(clips, size[0], size[1], output);

            ClipProbe probe = ffmpeg.probe(output);
            if (!probe.isPlayable()) {
                throw new ClipProcessingException("Joining the shots produced no usable video");
            }
            String objectKey = "films/%s/%s.mp4".formatted(render.getProjectId(), renderId);
            objectStore.upload(objectKey, output);
            finishSuccess(renderId, objectKey, probe, size, ordered);
            log.info("Joined a film renderId={} projectId={} shots={} seconds={}",
                    renderId, render.getProjectId(), ordered.size(), probe.durationSeconds());
        } catch (RuntimeException ex) {
            log.warn("Film assembly failed renderId={}: {}", renderId, ex.getMessage());
            finishFailure(renderId, ex.getMessage());
            throw ex;
        } finally {
            ffmpeg.deleteQuietly(workDir);
        }
    }

    /** The newest assembly of this project, whatever state it is in. */
    @Transactional(readOnly = true)
    public Optional<FilmRender> latest(UUID projectId) {
        return filmRenderRepository.findTopByProjectIdOrderByCreatedAtDesc(projectId);
    }

    /** The newest assembly the creator chose to show the client. */
    @Transactional(readOnly = true)
    public Optional<FilmRender> latestPublished(UUID projectId) {
        return filmRenderRepository.findTopByProjectIdAndPublishedIsTrueOrderByCreatedAtDesc(projectId);
    }

    /**
     * Shows this film on the client's review page, or takes it back down.
     *
     * <p>Only a finished assembly can be published: a QUEUED or FAILED render has nothing to show,
     * and publishing one would put an empty player in front of a client.
     */
    @Transactional
    public FilmRender setPublished(UUID tenantId, UUID renderId, boolean published) {
        FilmRender render = filmRenderRepository.findById(renderId)
                .filter(candidate -> tenantId.equals(candidate.getTenantId()))
                .orElseThrow(() -> new ClipProcessingException("No such film"));
        if (published && !render.isPlayable()) {
            throw new ClipProcessingException(
                    "This film is not finished yet, so there is nothing to show the client");
        }
        render.setPublished(published);
        render.setPublishedAt(published ? OffsetDateTime.now() : null);
        log.info("{} a film renderId={} projectId={}",
                published ? "Published" : "Unpublished", renderId, render.getProjectId());
        return filmRenderRepository.save(render);
    }

    public String playableUrl(FilmRender render) {
        return render.isPlayable() ? objectStore.presignedUrl(render.getBucket(), render.getObjectKey()) : null;
    }

    private Map<UUID, ShotClipVersion> activeCutsByShot(UUID projectId) {
        return clipVersionRepository.findByProjectIdAndStatus(projectId, ClipVersionStatus.ACTIVE).stream()
                .collect(Collectors.toMap(ShotClipVersion::getShotId, version -> version,
                        (first, second) -> first, LinkedHashMap::new));
    }

    /** The project's own shape, or the first shot's when it has no config yet. Both forced even --
     * H.264 refuses odd dimensions, and a film that will not encode is worse than one an aspect
     * ratio off. */
    private int[] targetSize(UUID tenantId, UUID projectId, List<ShotClipVersion> ordered) {
        String ratio = preProductionClient.getAspectRatio(tenantId, projectId);
        int[] size = ratio == null ? null : RATIO_SIZES.get(ratio);
        if (size == null) {
            ShotClipVersion first = ordered.get(0);
            size = new int[]{
                    first.getWidth() == null ? 1080 : first.getWidth(),
                    first.getHeight() == null ? 1920 : first.getHeight()};
        }
        return new int[]{size[0] - (size[0] % 2), size[1] - (size[1] % 2)};
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    protected FilmRender markProcessing(UUID renderId) {
        FilmRender render = filmRenderRepository.findById(renderId)
                .orElseThrow(() -> new ClipProcessingException("No such film render"));
        render.setStatus(FilmRenderStatus.PROCESSING);
        return filmRenderRepository.save(render);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    protected void finishSuccess(UUID renderId, String objectKey, ClipProbe probe, int[] size,
                                 List<ShotClipVersion> ordered) {
        FilmRender render = filmRenderRepository.findById(renderId).orElseThrow();
        render.setStatus(FilmRenderStatus.COMPLETED);
        render.setBucket(objectStore.bucket());
        render.setObjectKey(objectKey);
        render.setDurationSeconds(probe.durationSeconds());
        render.setWidth(size[0]);
        render.setHeight(size[1]);
        render.setShotCount(ordered.size());
        render.setSourceVersionIds(ordered.stream()
                .map(version -> version.getVersionId().toString())
                .collect(Collectors.joining(",")));
        render.setCompletedAt(OffsetDateTime.now());
        filmRenderRepository.save(render);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    protected void finishFailure(UUID renderId, String reason) {
        filmRenderRepository.findById(renderId).ifPresent(render -> {
            render.setStatus(FilmRenderStatus.FAILED);
            render.setLastError(reason);
            render.setCompletedAt(OffsetDateTime.now());
            filmRenderRepository.save(render);
        });
    }

    /**
     * Whether the film can be put together, and what is stopping it.
     *
     * @param missingShotRefs the shots with no cut yet -- named rather than counted, so the page can
     *                        say which ones to generate instead of only disabling a button.
     */
    public record Readiness(int total, int ready, List<String> missingShotRefs) {

        public boolean isReady() {
            return total > 0 && missingShotRefs.isEmpty();
        }
    }
}
