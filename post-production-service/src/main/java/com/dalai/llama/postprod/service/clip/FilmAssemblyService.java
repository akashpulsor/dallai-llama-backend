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
import com.dalai.llama.postprod.service.videogen.VideoGenShotJob;
import com.dalai.llama.postprod.service.videogen.VideoGenerationClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
    private final VideoGenerationClient videoGenerationClient;
    private final ShotClipVersionService clipVersionService;
    private final FfmpegClipProcessor ffmpeg;
    private final ClipObjectStore objectStore;

    /** What the page needs to decide whether the combine button is usable, without assembling
     * anything. Cheap: two reads and no ffmpeg. */
    @Transactional(readOnly = true)
    public Readiness readiness(UUID tenantId, UUID projectId) {
        List<PreProductionShotSummary> shots = preProductionClient.listShots(tenantId, projectId);
        Map<UUID, ShotClipVersion> cuts = activeCutsByShot(projectId);

        // A shot is ready if it has a chosen cut OR a finished render waiting to be imported.
        //
        // Versions are created lazily, the first time a shot is cut, which is right for cutting and
        // wrong here: a project with thirteen finished shots and no cuts has no version rows at all,
        // so this reported every single shot missing while every single one was generated. The
        // generated clip is the shot's video until someone makes a different one.
        java.util.Set<String> generated = generatedShotRefs(tenantId, projectId);
        List<String> missing = shots.stream()
                .filter(shot -> !cuts.containsKey(shot.id()))
                .filter(shot -> shot.shotRef() == null || !generated.contains(shot.shotRef()))
                .map(shot -> shot.shotRef() == null ? "(unnamed)" : shot.shotRef())
                .toList();
        return new Readiness(shots.size(), shots.size() - missing.size(), missing);
    }

    /** Shot refs whose render finished in video-generation-service. Empty rather than fatal when
     * that service cannot be reached -- readiness then falls back to what has actually been cut,
     * which understates rather than lies. */
    private java.util.Set<String> generatedShotRefs(UUID tenantId, UUID projectId) {
        try {
            return videoGenerationClient.listJobsForProject(tenantId, projectId).stream()
                    .filter(VideoGenShotJob::isCompleted)
                    .map(VideoGenShotJob::shotRef)
                    .filter(java.util.Objects::nonNull)
                    .collect(Collectors.toSet());
        } catch (RuntimeException ex) {
            log.warn("Could not read generated shots for project {}: {}", projectId, ex.getMessage());
            return java.util.Set.of();
        }
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
        // Published AFTER this transaction commits, never inside it.
        //
        // The row above is invisible to every other database connection until commit, and Kafka is
        // not in the transaction -- so publishing here sent the event while the row still did not
        // exist for anyone else. The consumer, on its own thread and its own connection, looked the
        // renderId up 300ms before the commit landed, found nothing, and dropped the event. The
        // film then sat QUEUED for ever and read as "joining is taking a long time". Every film
        // ever requested was lost this way: one queued, one dropped, none joined.
        UUID renderId = render.getRenderId();
        int shotCount = readiness.total();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    assemblyRequestedPublisher.publish(
                            new FilmAssemblyRequestedEvent(renderId, tenantId.toString(), projectId, userId));
                    log.info("Queued a film renderId={} projectId={} shots={}", renderId, projectId, shotCount);
                } catch (RuntimeException ex) {
                    // Past the commit the caller already has its 202, so throwing here would reach
                    // nobody. A QUEUED row with no event behind it would sit for ever, so the
                    // failure is recorded where the page is already looking.
                    log.warn("Could not queue the film renderId={}: {}", renderId, ex.getMessage());
                    markQueueFailed(renderId, ex.getMessage());
                }
            }
        });
        return render;
    }

    /** Records that the film could never be queued, in its own transaction -- the request's has
     * already committed by the time this runs. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markQueueFailed(UUID renderId, String reason) {
        filmRenderRepository.findById(renderId).ifPresent(render -> {
            render.setStatus(FilmRenderStatus.FAILED);
            render.setLastError("Could not queue the film for joining: " + reason);
            render.setCompletedAt(OffsetDateTime.now());
            filmRenderRepository.save(render);
        });
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

            // Any shot that has never been cut is imported at its generated clip first, so the film
            // contains every shot whether or not the creator chose to change its sound. Done here
            // rather than demanded up front: making a film is exactly when a shot that was only ever
            // generated needs to become a version, and asking a creator to "cut" thirteen shots they
            // are happy with before joining them would be busywork.
            List<PreProductionShotSummary> ordered = shots.stream()
                    .sorted(Comparator.comparing(shot ->
                            Optional.ofNullable(shot.shotNumber()).orElse(Integer.MAX_VALUE)))
                    .toList();
            Map<UUID, ShotClipVersion> cuts = activeCutsByShot(render.getProjectId());
            List<ShotClipVersion> cutsInOrder = new java.util.ArrayList<>(ordered.size());
            for (PreProductionShotSummary shot : ordered) {
                ShotClipVersion cut = cuts.get(shot.id());
                if (cut == null) {
                    cut = clipVersionService.importGeneratedBaseline(new ShotClipVersionService.Context(
                            render.getTenantId(), render.getProjectId(), shot.id(), shot.shotRef(),
                            render.getCreatedBy()));
                }
                cutsInOrder.add(cut);
            }

            // Signed URLs, not downloads. ffmpeg reads each shot from MinIO as it needs it, so the
            // join starts on the first frame instead of after every clip has landed on disk --
            // thirteen serial downloads that bought nothing, since ffmpeg has to read the bytes
            // either way. Signed internally: these are for a process in this pod, not a browser.
            List<String> clips = new java.util.ArrayList<>(cutsInOrder.size());
            for (ShotClipVersion cut : cutsInOrder) {
                clips.add(objectStore.internalPresignedUrl(cut.getBucket(), cut.getObjectKey()));
            }

            int[] size = targetSize(render.getTenantId(), render.getProjectId(), cutsInOrder);
            Path output = workDir.resolve("film.mp4");
            ffmpeg.concat(clips, size[0], size[1], output);

            ClipProbe probe = ffmpeg.probe(output);
            if (!probe.isPlayable()) {
                throw new ClipProcessingException("Joining the shots produced no usable video");
            }
            String objectKey = "films/%s/%s.mp4".formatted(render.getProjectId(), renderId);
            objectStore.upload(objectKey, output);
            finishSuccess(renderId, objectKey, probe, size, cutsInOrder);
            log.info("Joined a film renderId={} projectId={} shots={} seconds={}",
                    renderId, render.getProjectId(), cutsInOrder.size(), probe.durationSeconds());
        } catch (RuntimeException ex) {
            log.warn("Film assembly failed renderId={}: {}", renderId, ex.getMessage());
            finishFailure(renderId, ex.getMessage());
            throw ex;
        } finally {
            ffmpeg.deleteQuietly(workDir);
        }
    }

    /**
     * Where this film sits in the queue, and roughly how long that means waiting.
     *
     * <p>Films are joined one at a time -- every tenant's request goes onto one topic and is
     * consumed serially -- so a creator can be waiting behind work that is not theirs and has no way
     * to tell. An unexplained spinner reads as broken long before it reads as third in line.
     *
     * <p>The count is across ALL tenants on purpose: that is the queue actually being waited in, and
     * counting only a creator's own films would tell someone who is sixth that they are next. It
     * discloses a number and nothing else.
     *
     * <p>The estimate is built from how long recent joins TOOK, measured start to finish, never from
     * created-to-finished -- that would fold other people's queue time into the encode and make every
     * estimate longer the busier things got. Null rather than a guess when nothing has finished yet;
     * a made-up number is worse than an honest "not known".
     */
    @Transactional(readOnly = true)
    public QueueWait queueWait(FilmRender render) {
        if (render.getStatus() != FilmRenderStatus.QUEUED) {
            return new QueueWait(0, null);
        }
        long ahead = filmRenderRepository.countByStatusAndCreatedAtLessThan(
                FilmRenderStatus.QUEUED, render.getCreatedAt());
        Long typical = typicalJoinSeconds();
        // +1 for this film's own join. The film currently being worked on is counted as a whole one
        // rather than tracking how far through it is: overstating a wait slightly is the kinder error.
        Long estimate = typical == null ? null : (ahead + 2) * typical;
        return new QueueWait((int) ahead, estimate);
    }

    /** The middle of the last ten joins, not the mean -- one pathological film should not move it. */
    private Long typicalJoinSeconds() {
        List<FilmRender> finished = filmRenderRepository.recentFinished(
                FilmRenderStatus.COMPLETED, org.springframework.data.domain.PageRequest.of(0, 10));
        List<Long> durations = finished.stream()
                .map(render -> java.time.Duration.between(render.getStartedAt(), render.getCompletedAt()).toSeconds())
                .filter(seconds -> seconds > 0)
                .sorted()
                .toList();
        return durations.isEmpty() ? null : durations.get(durations.size() / 2);
    }

    /**
     * @param filmsAhead           how many films are queued in front of this one, across every tenant
     * @param estimatedWaitSeconds a rough total wait including this film's own join, or null when no
     *                             join has ever finished and there is nothing to estimate from
     */
    public record QueueWait(int filmsAhead, Long estimatedWaitSeconds) {
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
        FilmRender saved = filmRenderRepository.save(render);

        // The project moves to READY_FOR_REVIEW with it, so a list of projects says where the work
        // actually is. Best-effort on purpose: the film IS published the moment the row says so, and
        // failing the creator's press because a status call did not land would be the wrong trade.
        // Only on the way up -- unpublishing does not walk a project backwards, since a client who
        // has already seen it has still seen it.
        if (published) {
            try {
                preProductionClient.markReadyForReview(tenantId, saved.getProjectId());
            } catch (RuntimeException ex) {
                log.warn("Published the film but could not mark project {} ready for review: {}",
                        saved.getProjectId(), ex.getMessage());
            }
        }
        log.info("{} a film renderId={} projectId={}",
                published ? "Published" : "Unpublished", renderId, saved.getProjectId());
        return saved;
    }

    /**
     * The creator's own edit of the film, brought back after cutting it elsewhere.
     *
     * <p>Recorded as a NEW film render rather than overwriting the last one, so the joined version
     * and the hand-edited version both survive and publishing can move between them. Published
     * state deliberately does not carry over: a client watching the previous cut keeps watching it
     * until the creator says otherwise.
     */
    public FilmRender uploadEdited(UUID tenantId, UUID projectId, UUID userId,
                                    org.springframework.web.multipart.MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ClipProcessingException("No file was uploaded");
        }
        Path workDir = ffmpeg.createWorkDir("film-upload-" + projectId);
        try {
            Path uploaded = workDir.resolve("film.mp4");
            try {
                file.transferTo(uploaded);
            } catch (Exception ex) {
                throw new ClipProcessingException("Could not read the uploaded file: " + ex.getMessage(), ex);
            }
            ClipProbe probe = ffmpeg.probe(uploaded);
            if (!probe.isPlayable()) {
                throw new ClipProcessingException(
                        "That file has no usable video in it -- the film is unchanged");
            }
            UUID renderId = UUID.randomUUID();
            String objectKey = "films/%s/%s.mp4".formatted(projectId, renderId);
            objectStore.upload(objectKey, uploaded);
            FilmRender render = filmRenderRepository.save(FilmRender.builder()
                    .renderId(renderId)
                    .tenantId(tenantId)
                    .projectId(projectId)
                    .status(FilmRenderStatus.COMPLETED)
                    .bucket(objectStore.bucket())
                    .objectKey(objectKey)
                    .durationSeconds(probe.durationSeconds())
                    .width(probe.width())
                    .height(probe.height())
                    .createdBy(userId)
                    .createdAt(OffsetDateTime.now())
                    .completedAt(OffsetDateTime.now())
                    .build());
            log.info("Creator supplied their own film projectId={} renderId={} seconds={}",
                    projectId, renderId, probe.durationSeconds());
            return render;
        } finally {
            ffmpeg.deleteQuietly(workDir);
        }
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
        render.setStartedAt(OffsetDateTime.now());
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
