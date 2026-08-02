package com.dalai.llama.creator.service;

import com.dalai.llama.creator.domain.entity.CreatorAsset;
import com.dalai.llama.creator.domain.entity.CreatorShortVideo;
import com.dalai.llama.creator.dto.request.TimelineIngestionSessionRequest;
import com.dalai.llama.creator.dto.response.TimelineIngestionResponse;
import com.dalai.llama.creator.repository.CreatorAssetRepository;
import com.dalai.llama.creator.repository.CreatorShortVideoRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class CreatorTimelineIngestionService {

    private static final Logger log = LoggerFactory.getLogger(CreatorTimelineIngestionService.class);
    private static final AtomicInteger WORKER_COUNTER = new AtomicInteger(1);
    private static final ThreadFactory WORKER_THREAD_FACTORY = runnable -> {
        Thread thread = new Thread(runnable, "creator-timeline-ingestion-" + WORKER_COUNTER.getAndIncrement());
        thread.setDaemon(true);
        return thread;
    };

    private static final Duration SIGNED_URL_TTL = Duration.ofDays(7);
    private static final Duration FFMPEG_TIMEOUT = Duration.ofMinutes(12);
    private static final long MAX_TIMELINE_PART_BYTES = 1024L * 1024L * 1024L;
    private static final int MAX_THUMBNAILS_PER_PART = 160;
    private static final int MAX_SCENE_DETECTION_FRAMES = 160;
    private static final double DEFAULT_PART_DURATION_SECONDS = 10 * 60.0;
    private static final double DEFAULT_SCENE_WINDOW_SECONDS = 30.0;
    private static final double DEFAULT_THUMBNAIL_INTERVAL_SECONDS = 5.0;
    private static final double SCENE_THRESHOLD = 0.35;
    private static final String ASSET_TYPE_SOURCE_PART = "TIMELINE_SOURCE_PART";
    private static final String ASSET_TYPE_THUMBNAIL = "TIMELINE_THUMBNAIL";

    private final CreatorShortVideoRepository videoRepository;
    private final CreatorAssetRepository assetRepository;
    private final AssetStorageService assetStorageService;
    private final GoogleShortFullTranscriptWorkerService fullTranscriptWorkerService;
    private final ShortSceneAnalysisService sceneAnalysisService;
    private final TransactionTemplate transactionTemplate;
    private final ExecutorService executor = Executors.newFixedThreadPool(1, WORKER_THREAD_FACTORY);

    public CreatorTimelineIngestionService(
            CreatorShortVideoRepository videoRepository,
            CreatorAssetRepository assetRepository,
            AssetStorageService assetStorageService,
            GoogleShortFullTranscriptWorkerService fullTranscriptWorkerService,
            ShortSceneAnalysisService sceneAnalysisService,
            TransactionTemplate transactionTemplate
    ) {
        this.videoRepository = videoRepository;
        this.assetRepository = assetRepository;
        this.assetStorageService = assetStorageService;
        this.fullTranscriptWorkerService = fullTranscriptWorkerService;
        this.sceneAnalysisService = sceneAnalysisService;
        this.transactionTemplate = transactionTemplate;
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    @Transactional
    public TimelineIngestionResponse createSession(TimelineIngestionSessionRequest request, String tenantId, String userId) {
        TimelineIngestionSessionRequest safeRequest = request == null
                ? new TimelineIngestionSessionRequest(null, null, null, null, null, null, null, null, null, null)
                : request;
        String safeTenantId = safeTenantId(tenantId);
        String safeUserId = safeUserId(userId);
        UUID videoId = UUID.randomUUID();
        UUID uploadId = UUID.randomUUID();
        String title = defaultString(safeRequest.title(), stripExtension(defaultString(safeRequest.originalFilename(), "Timeline upload")));
        double partDuration = positiveDouble(safeRequest.partDurationSeconds(), DEFAULT_PART_DURATION_SECONDS);
        double sceneWindow = clampDouble(positiveDouble(safeRequest.sceneWindowSeconds(), DEFAULT_SCENE_WINDOW_SECONDS), 5.0, 120.0);
        double thumbnailInterval = clampDouble(positiveDouble(safeRequest.thumbnailIntervalSeconds(), DEFAULT_THUMBNAIL_INTERVAL_SECONDS), 1.0, 30.0);
        int expectedParts = Math.max(1, intValue(safeRequest.expectedParts(), 1));

        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("mode", "TIMELINE_INGESTION");
        settings.put("uploadId", uploadId.toString());
        settings.put("expectedParts", expectedParts);
        settings.put("partDurationSeconds", round3(partDuration));
        settings.put("sceneWindowSeconds", round3(sceneWindow));
        settings.put("thumbnailIntervalSeconds", round3(thumbnailInterval));

        Map<String, Object> ingestion = new LinkedHashMap<>();
        ingestion.put("uploadId", uploadId.toString());
        ingestion.put("status", "READY");
        ingestion.put("expectedParts", expectedParts);
        ingestion.put("receivedParts", 0);
        ingestion.put("processedParts", 0);
        ingestion.put("partDurationSeconds", round3(partDuration));
        ingestion.put("sceneWindowSeconds", round3(sceneWindow));
        ingestion.put("thumbnailIntervalSeconds", round3(thumbnailInterval));
        ingestion.put("originalFilename", defaultString(safeRequest.originalFilename(), "source-video.mp4"));
        ingestion.put("contentType", defaultString(safeRequest.contentType(), "application/octet-stream"));
        ingestion.put("sizeBytes", safeRequest.sizeBytes() == null ? 0L : Math.max(0L, safeRequest.sizeBytes()));
        ingestion.put("sourceParts", new ArrayList<Map<String, Object>>());
        ingestion.put("trace", new ArrayList<Map<String, Object>>());
        ingestion.put("stageStates", initialStageStates());
        ingestion.put("createdAt", OffsetDateTime.now().toString());
        ingestion.put("updatedAt", OffsetDateTime.now().toString());
        ingestion.put("timelineProject", timelineProject(videoId, title, ingestion));

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("timelineIngestion", ingestion);
        metadata.put("timelineIngestionProject", ingestion.get("timelineProject"));

        CreatorShortVideo video = CreatorShortVideo.builder()
                .id(videoId)
                .tenantId(safeTenantId)
                .userId(safeUserId)
                .projectId(safeRequest.projectId())
                .title(title)
                .originalFileName(defaultString(safeRequest.originalFilename(), "source-video.mp4"))
                .platform(defaultString(safeRequest.platform(), "timeline_editor"))
                .targetDurationSeconds(0)
                .requestedShorts(0)
                .reviewMode("TIMELINE_ONLY")
                .status("TIMELINE_UPLOAD_READY")
                .settings(settings)
                .metadata(metadata)
                .build();
        CreatorShortVideo saved = videoRepository.saveAndFlush(video);
        return toResponse(saved);
    }

    public TimelineIngestionResponse uploadPart(
            UUID videoId,
            int partNumber,
            MultipartFile part,
            Double partStartSeconds,
            Double declaredDurationSeconds,
            boolean finalPart,
            String tenantId,
            String userId
    ) {
        if (partNumber < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Part number must start at 1.");
        }
        if (part == null || part.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Timeline ingestion part is required.");
        }
        if (part.getSize() > MAX_TIMELINE_PART_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Timeline ingestion part is too large. Use playable parts up to 1 GB.");
        }
        String safeTenantId = safeTenantId(tenantId);
        String safeUserId = safeUserId(userId);
        CreatorShortVideo video = findVideo(videoId, safeTenantId, safeUserId);
        Map<String, Object> ingestion = ingestionState(video);
        if (partByNumber(sourceParts(ingestion), partNumber) != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Timeline ingestion part " + partNumber + " was already uploaded.");
        }

        Path partPath = null;
        try {
            String originalFilename = defaultString(part.getOriginalFilename(), "part-" + partNumber + ".mp4");
            String contentType = defaultString(part.getContentType(), defaultString(stringValue(ingestion.get("contentType")), "application/octet-stream"));
            partPath = Files.createTempFile("creator-timeline-part-" + videoId + "-" + partNumber + "-", extensionFor(contentType, originalFilename));
            part.transferTo(partPath);

            String objectKey = sourcePartObjectKey(safeTenantId, safeUserId, videoId, partNumber, originalFilename);
            AssetStorageService.StoredObject stored = assetStorageService.uploadCreatorAssetFromPath(objectKey, partPath, contentType, SIGNED_URL_TTL);
            CreatorAsset sourcePartAsset = assetRepository.saveAndFlush(CreatorAsset.builder()
                    .id(UUID.randomUUID())
                    .tenantId(safeTenantId)
                    .userId(safeUserId)
                    .projectId(video.getProjectId())
                    .assetType(ASSET_TYPE_SOURCE_PART)
                    .bucket(stored.bucket())
                    .objectKey(stored.objectKey())
                    .contentType(stored.contentType())
                    .sizeBytes(stored.sizeBytes())
                    .publicUrl(stored.signedUrl())
                    .metadata(Map.of(
                            "videoId", videoId.toString(),
                            "partNumber", partNumber,
                            "timelineIngestion", true
                    ))
                    .build());

            double defaultPartDuration = doubleValue(ingestion.get("partDurationSeconds"), DEFAULT_PART_DURATION_SECONDS);
            double startSeconds = partStartSeconds == null
                    ? (partNumber - 1) * defaultPartDuration
                    : Math.max(0.0, partStartSeconds);
            Map<String, Object> partState = new LinkedHashMap<>();
            partState.put("partNumber", partNumber);
            partState.put("status", "UPLOADED");
            partState.put("sourceAssetId", sourcePartAsset.getId().toString());
            partState.put("bucket", stored.bucket());
            partState.put("objectKey", stored.objectKey());
            partState.put("url", stored.signedUrl());
            partState.put("contentType", stored.contentType());
            partState.put("sizeBytes", stored.sizeBytes());
            partState.put("start", round3(startSeconds));
            partState.put("declaredDurationSeconds", declaredDurationSeconds == null ? null : round3(Math.max(0.0, declaredDurationSeconds)));
            partState.put("end", declaredDurationSeconds == null ? round3(startSeconds + defaultPartDuration) : round3(startSeconds + Math.max(0.1, declaredDurationSeconds)));
            partState.put("finalPart", finalPart);
            partState.put("uploadedAt", OffsetDateTime.now().toString());
            partState.put("sceneClips", new ArrayList<Map<String, Object>>());
            partState.put("thumbnailClips", new ArrayList<Map<String, Object>>());

            CreatorShortVideo saved = transactionTemplate.execute(status -> {
                CreatorShortVideo locked = findVideo(videoId, safeTenantId, safeUserId);
                Map<String, Object> lockedIngestion = ingestionState(locked);
                List<Map<String, Object>> parts = sourceParts(lockedIngestion);
                parts.add(partState);
                parts.sort(Comparator.comparingInt(item -> intValue(item.get("partNumber"), 0)));
                lockedIngestion.put("sourceParts", parts);
                lockedIngestion.put("receivedParts", parts.size());
                if (finalPart) {
                    lockedIngestion.put("expectedParts", partNumber);
                    lockedIngestion.put("finalPartReceived", true);
                }
                updateAggregateStatus(lockedIngestion);
                putStageState(lockedIngestion, "upload", "COMPLETED", "Playable source part " + partNumber + " uploaded. Timeline rendering has not started.", Map.of(
                        "partNumber", partNumber,
                        "receivedParts", parts.size(),
                        "expectedParts", intValue(lockedIngestion.get("expectedParts"), parts.size())
                ));
                addTrace(lockedIngestion, "PART_UPLOADED", "Part " + partNumber + " stored. FFmpeg timeline extraction waits for the explicit fabric timeline stage.", Map.of(
                        "partNumber", partNumber,
                        "sourceAssetId", sourcePartAsset.getId().toString(),
                        "sizeBytes", stored.sizeBytes()
                ));
                if (locked.getSourceAssetId() == null || partNumber == 1) {
                    locked.setSourceAssetId(sourcePartAsset.getId());
                }
                persistIngestion(locked, lockedIngestion, "TIMELINE_UPLOADED");
                return videoRepository.saveAndFlush(locked);
            });

            return toResponse(saved);
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not receive timeline ingestion part.", ex);
        } finally {
            deleteQuietly(partPath);
        }
    }

    @Transactional(readOnly = true)
    public TimelineIngestionResponse getSession(UUID videoId, String tenantId, String userId) {
        return toResponse(findVideo(videoId, safeTenantId(tenantId), safeUserId(userId)));
    }

    public TimelineIngestionResponse requestFabricTimeline(UUID videoId, Map<String, Object> payload, String tenantId, String userId) {
        String safeTenantId = safeTenantId(tenantId);
        String safeUserId = safeUserId(userId);
        List<Integer> queuedPartNumbers = new ArrayList<>();
        CreatorShortVideo saved = transactionTemplate.execute(status -> {
            CreatorShortVideo video = findVideo(videoId, safeTenantId, safeUserId);
            Map<String, Object> ingestion = ingestionState(video);
            List<Map<String, Object>> parts = sourceParts(ingestion);
            if (parts.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Upload at least one playable part before rendering the Fabric timeline.");
            }
            for (Map<String, Object> part : parts) {
                String partStatus = stringValue(part.get("status"));
                if (!"PROCESSED".equalsIgnoreCase(partStatus) && !"ANALYZING".equalsIgnoreCase(partStatus) && !"QUEUED".equalsIgnoreCase(partStatus)) {
                    part.put("status", "QUEUED");
                    queuedPartNumbers.add(intValue(part.get("partNumber"), 0));
                }
            }
            updateAggregateStatus(ingestion);
            putStageState(ingestion, "fabricTimeline", queuedPartNumbers.isEmpty() ? "COMPLETED" : "RUNNING",
                    queuedPartNumbers.isEmpty() ? "Fabric timeline is already ready." : "FFmpeg thumbnail and scene extraction queued part-by-part.",
                    Map.of("queuedParts", queuedPartNumbers));
            addTrace(ingestion, "FABRIC_TIMELINE_REQUESTED", "Fabric timeline rendering requested explicitly after upload.", Map.of(
                    "queuedParts", queuedPartNumbers,
                    "partCount", parts.size()
            ));
            persistIngestion(video, ingestion, queuedPartNumbers.isEmpty() ? "TIMELINE_READY" : "TIMELINE_RENDERING");
            return videoRepository.saveAndFlush(video);
        });
        for (Integer partNumber : queuedPartNumbers) {
            if (partNumber != null && partNumber > 0) {
                executor.submit(() -> processPart(videoId, safeTenantId, safeUserId, partNumber));
            }
        }
        return toResponse(saved);
    }

    public TimelineIngestionResponse requestTranscriptTimeline(UUID videoId, Map<String, Object> payload, String tenantId, String userId) {
        String safeTenantId = safeTenantId(tenantId);
        String safeUserId = safeUserId(userId);
        CreatorShortVideo saved = transactionTemplate.execute(status -> {
            CreatorShortVideo video = findVideo(videoId, safeTenantId, safeUserId);
            Map<String, Object> ingestion = ingestionState(video);
            if (sourceParts(ingestion).isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Upload at least one playable part before generating transcript timeline.");
            }
            putStageState(ingestion, "transcriptTimeline", "RUNNING", "Transcript timeline queued. Parts will be transcribed sequentially.", Map.of(
                    "partCount", sourceParts(ingestion).size()
            ));
            addTrace(ingestion, "TRANSCRIPT_TIMELINE_REQUESTED", "Transcript timeline generation requested after Fabric timeline.", Map.of(
                    "partCount", sourceParts(ingestion).size()
            ));
            persistIngestion(video, ingestion, "TIMELINE_TRANSCRIPT_RUNNING");
            return videoRepository.saveAndFlush(video);
        });
        executor.submit(() -> processTranscriptTimeline(videoId, safeTenantId, safeUserId));
        return toResponse(saved);
    }

    public TimelineIngestionResponse requestVideoAnalysis(UUID videoId, Map<String, Object> payload, String tenantId, String userId) {
        String safeTenantId = safeTenantId(tenantId);
        String safeUserId = safeUserId(userId);
        CreatorShortVideo saved = transactionTemplate.execute(status -> {
            CreatorShortVideo video = findVideo(videoId, safeTenantId, safeUserId);
            Map<String, Object> ingestion = ingestionState(video);
            if (sourceParts(ingestion).isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Upload at least one playable part before video analysis.");
            }
            putStageState(ingestion, "videoAnalysis", "RUNNING", "Video analysis queued. Parts will be analyzed sequentially.", Map.of(
                    "partCount", sourceParts(ingestion).size()
            ));
            addTrace(ingestion, "VIDEO_ANALYSIS_REQUESTED", "Video analysis requested after transcript timeline.", Map.of(
                    "partCount", sourceParts(ingestion).size()
            ));
            persistIngestion(video, ingestion, "TIMELINE_VIDEO_ANALYSIS_RUNNING");
            return videoRepository.saveAndFlush(video);
        });
        executor.submit(() -> processVideoAnalysis(videoId, safeTenantId, safeUserId));
        return toResponse(saved);
    }

    public TimelineIngestionResponse requestStoryShots(UUID videoId, Map<String, Object> payload, String tenantId, String userId) {
        String safeTenantId = safeTenantId(tenantId);
        String safeUserId = safeUserId(userId);
        CreatorShortVideo saved = transactionTemplate.execute(status -> {
            CreatorShortVideo video = findVideo(videoId, safeTenantId, safeUserId);
            Map<String, Object> ingestion = ingestionState(video);
            if (sourceParts(ingestion).isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Upload at least one playable part before story and shot planning.");
            }
            putStageState(ingestion, "storyShots", "RUNNING", "Story and shot planning queued from transcript plus video analysis.", Map.of(
                    "partCount", sourceParts(ingestion).size()
            ));
            addTrace(ingestion, "STORY_SHOTS_REQUESTED", "Story candidates and shot timeline requested after video analysis.", Map.of(
                    "partCount", sourceParts(ingestion).size()
            ));
            persistIngestion(video, ingestion, "TIMELINE_STORY_SHOTS_RUNNING");
            return videoRepository.saveAndFlush(video);
        });
        executor.submit(() -> processStoryShots(videoId, safeTenantId, safeUserId));
        return toResponse(saved);
    }

    public TimelineIngestionResponse completeSession(UUID videoId, Map<String, Object> payload, String tenantId, String userId) {
        String safeTenantId = safeTenantId(tenantId);
        String safeUserId = safeUserId(userId);
        CreatorShortVideo saved = transactionTemplate.execute(status -> {
            CreatorShortVideo video = findVideo(videoId, safeTenantId, safeUserId);
            Map<String, Object> ingestion = ingestionState(video);
            if (payload != null && payload.get("expectedParts") != null) {
                ingestion.put("expectedParts", Math.max(1, intValue(payload.get("expectedParts"), intValue(ingestion.get("expectedParts"), 1))));
            }
            ingestion.put("completeRequested", true);
            updateAggregateStatus(ingestion);
            putStageState(ingestion, "upload", "COMPLETED", "Upload marked complete. Fabric timeline remains an explicit next step.", Map.of(
                    "receivedParts", intValue(ingestion.get("receivedParts"), 0),
                    "expectedParts", intValue(ingestion.get("expectedParts"), 0)
            ));
            String nextStatus = "READY".equals(stringValue(ingestion.get("status"))) ? "TIMELINE_READY" : "TIMELINE_UPLOADED";
            addTrace(ingestion, "COMPLETE_REQUESTED", "Timeline ingestion completion requested.", Map.of(
                    "receivedParts", intValue(ingestion.get("receivedParts"), 0),
                    "processedParts", intValue(ingestion.get("processedParts"), 0),
                    "expectedParts", intValue(ingestion.get("expectedParts"), 0)
            ));
            persistIngestion(video, ingestion, nextStatus);
            return videoRepository.saveAndFlush(video);
        });
        return toResponse(saved);
    }

    private void processPart(UUID videoId, String tenantId, String userId, int partNumber) {
        try {
            CreatorShortVideo video = findVideo(videoId, tenantId, userId);
            Map<String, Object> ingestion = ingestionState(video);
            Map<String, Object> partState = partByNumber(sourceParts(ingestion), partNumber);
            if (partState == null) {
                throw new IllegalStateException("Timeline part state disappeared for part " + partNumber + ".");
            }
            String sourceInput = defaultString(firstNonBlank(partState.get("url"), partState.get("publicUrl"), partState.get("signedUrl")), "");
            if (sourceInput.isBlank()) {
                throw new IllegalStateException("Timeline part " + partNumber + " does not have a playable source URL.");
            }

            MediaInfo mediaInfo = probe(sourceInput);
            transactionTemplate.executeWithoutResult(status -> markPartStatus(videoId, tenantId, userId, partNumber, "ANALYZING", Map.of(
                    "durationSeconds", round3(mediaInfo.durationSeconds()),
                    "width", mediaInfo.width(),
                    "height", mediaInfo.height()
            )));

            video = findVideo(videoId, tenantId, userId);
            ingestion = ingestionState(video);
            partState = partByNumber(sourceParts(ingestion), partNumber);
            if (partState == null) {
                throw new IllegalStateException("Timeline part state disappeared for part " + partNumber + ".");
            }
            double partStart = doubleValue(partState.get("start"), (partNumber - 1) * DEFAULT_PART_DURATION_SECONDS);
            double duration = mediaInfo.durationSeconds() > 0
                    ? mediaInfo.durationSeconds()
                    : Math.max(0.1, doubleValue(partState.get("declaredDurationSeconds"), DEFAULT_PART_DURATION_SECONDS));
            double sceneWindow = clampDouble(doubleValue(ingestion.get("sceneWindowSeconds"), DEFAULT_SCENE_WINDOW_SECONDS), 5.0, 120.0);
            double thumbnailInterval = clampDouble(doubleValue(ingestion.get("thumbnailIntervalSeconds"), DEFAULT_THUMBNAIL_INTERVAL_SECONDS), 1.0, 30.0);

            List<Map<String, Object>> scenes = sceneClips(sourceInput, partNumber, partStart, duration, sceneWindow);
            List<Map<String, Object>> thumbnails = thumbnailClips(video, partState, sourceInput, partNumber, partStart, duration, thumbnailInterval, mediaInfo);

            transactionTemplate.executeWithoutResult(status -> {
                CreatorShortVideo locked = findVideo(videoId, tenantId, userId);
                Map<String, Object> lockedIngestion = ingestionState(locked);
                Map<String, Object> lockedPart = partByNumber(sourceParts(lockedIngestion), partNumber);
                if (lockedPart == null) {
                    throw new IllegalStateException("Timeline part state disappeared for part " + partNumber + ".");
                }
                lockedPart.put("status", "PROCESSED");
                lockedPart.put("durationSeconds", round3(duration));
                lockedPart.put("end", round3(partStart + duration));
                lockedPart.put("width", mediaInfo.width());
                lockedPart.put("height", mediaInfo.height());
                lockedPart.put("aspectRatio", mediaInfo.aspectRatio());
                lockedPart.put("sceneClips", scenes);
                lockedPart.put("thumbnailClips", thumbnails);
                lockedPart.put("processedAt", OffsetDateTime.now().toString());
                addTrace(lockedIngestion, "PART_PROCESSED", "Part " + partNumber + " timeline clips generated.", Map.of(
                        "partNumber", partNumber,
                        "sceneCount", scenes.size(),
                        "thumbnailCount", thumbnails.size(),
                        "durationSeconds", round3(duration)
                ));
                updateAggregateStatus(lockedIngestion);
                if ("READY".equals(stringValue(lockedIngestion.get("status")))) {
                    putStageState(lockedIngestion, "fabricTimeline", "COMPLETED", "Fabric timeline rendered from uploaded playable parts.", Map.of(
                            "processedParts", intValue(lockedIngestion.get("processedParts"), 0),
                            "expectedParts", intValue(lockedIngestion.get("expectedParts"), 0)
                    ));
                }
                String nextStatus = "READY".equals(stringValue(lockedIngestion.get("status"))) ? "TIMELINE_READY" : "TIMELINE_RENDERING";
                persistIngestion(locked, lockedIngestion, nextStatus);
                videoRepository.saveAndFlush(locked);
            });
        } catch (Exception ex) {
            log.warn("Timeline ingestion part processing failed videoId={} partNumber={} message={}", videoId, partNumber, ex.getMessage());
            transactionTemplate.executeWithoutResult(status -> markPartStatus(videoId, tenantId, userId, partNumber, "FAILED", Map.of(
                    "error", safeMessage(ex)
            )));
        }
    }

    private void processTranscriptTimeline(UUID videoId, String tenantId, String userId) {
        try {
            CreatorShortVideo video = findVideo(videoId, tenantId, userId);
            Map<String, Object> ingestion = ingestionState(video);
            List<Map<String, Object>> parts = sourceParts(ingestion);
            if (parts.isEmpty()) {
                throw new IllegalStateException("No uploaded parts are available for transcript timeline generation.");
            }

            List<Map<String, Object>> transcript = new ArrayList<>();
            List<Map<String, Object>> trace = new ArrayList<>();
            int nextNodeIndex = 1;
            for (Map<String, Object> part : parts) {
                int partNumber = intValue(part.get("partNumber"), 1);
                double partStart = doubleValue(part.get("start"), Math.max(0, partNumber - 1) * DEFAULT_PART_DURATION_SECONDS);
                CreatorAsset sourceAsset = sourceAssetForPart(video, part);
                GoogleShortFullTranscriptWorkerService.FullTranscriptResult result = fullTranscriptWorkerService.transcribe(video, sourceAsset);
                trace.addAll(listValue(result.trace()));
                List<Map<String, Object>> shifted = offsetTranscriptNodes(result.transcript(), partStart, partNumber, nextNodeIndex);
                transcript.addAll(shifted);
                nextNodeIndex += shifted.size();
            }

            Map<String, Object> graph = transcriptTimelineGraph(transcript, "timeline_ingestion_transcript");
            transactionTemplate.executeWithoutResult(status -> {
                CreatorShortVideo locked = findVideo(videoId, tenantId, userId);
                Map<String, Object> lockedIngestion = ingestionState(locked);
                Map<String, Object> transcriptTimeline = new LinkedHashMap<>();
                transcriptTimeline.put("status", transcript.isEmpty() ? "WARN" : "COMPLETED");
                transcriptTimeline.put("source", "timeline_ingestion_transcript");
                transcriptTimeline.put("generatedAt", OffsetDateTime.now().toString());
                transcriptTimeline.put("nodeCount", transcript.size());
                transcriptTimeline.put("nodes", transcript);
                transcriptTimeline.put("graph", graph);
                transcriptTimeline.put("trace", trace);
                lockedIngestion.put("transcriptTimeline", transcriptTimeline);
                putStageState(lockedIngestion, "transcriptTimeline", transcript.isEmpty() ? "WARN" : "COMPLETED",
                        transcript.isEmpty() ? "Transcript stage finished but no speech nodes were detected." : "Transcript timeline generated from uploaded parts.",
                        Map.of("nodeCount", transcript.size(), "partCount", sourceParts(lockedIngestion).size()));
                addTrace(lockedIngestion, "TRANSCRIPT_TIMELINE_COMPLETED", "Transcript timeline stage finished.", Map.of(
                        "nodeCount", transcript.size(),
                        "partCount", sourceParts(lockedIngestion).size()
                ));
                locked.setTranscriptPayload(Map.of(
                        "source", "timeline_ingestion_transcript",
                        "nodes", transcript,
                        "generatedAt", OffsetDateTime.now().toString()
                ));
                locked.setGraphPayload(graph);
                persistIngestion(locked, lockedIngestion, "TIMELINE_TRANSCRIPT_READY");
                videoRepository.saveAndFlush(locked);
            });
        } catch (Exception ex) {
            log.warn("Timeline transcript stage failed videoId={} message={}", videoId, ex.getMessage());
            markStageFailed(videoId, tenantId, userId, "transcriptTimeline", "TIMELINE_TRANSCRIPT_FAILED", safeMessage(ex));
        }
    }

    private void processVideoAnalysis(UUID videoId, String tenantId, String userId) {
        try {
            CreatorShortVideo video = findVideo(videoId, tenantId, userId);
            Map<String, Object> ingestion = ingestionState(video);
            List<Map<String, Object>> parts = sourceParts(ingestion);
            if (parts.isEmpty()) {
                throw new IllegalStateException("No uploaded parts are available for video analysis.");
            }

            List<Map<String, Object>> transcript = transcriptNodesForAnalysis(video, ingestion);
            List<Map<String, Object>> scenes = new ArrayList<>();
            List<Map<String, Object>> frames = new ArrayList<>();
            List<Map<String, Object>> attachedTranscript = new ArrayList<>();
            List<Map<String, Object>> trace = new ArrayList<>();
            List<Map<String, Object>> partMetadata = new ArrayList<>();
            for (Map<String, Object> part : parts) {
                int partNumber = intValue(part.get("partNumber"), 1);
                double partStart = doubleValue(part.get("start"), Math.max(0, partNumber - 1) * DEFAULT_PART_DURATION_SECONDS);
                double partEnd = Math.max(partStart, doubleValue(part.get("end"), partStart + DEFAULT_PART_DURATION_SECONDS));
                CreatorAsset sourceAsset = sourceAssetForPart(video, part);
                List<Map<String, Object>> localTranscript = localTranscriptForPart(transcript, partStart, partEnd, partNumber);
                ShortSceneAnalysisService.SceneAnalysisResult result = sceneAnalysisService.analyze(video, sourceAsset, localTranscript);
                scenes.addAll(offsetSceneMaps(result.scenes(), partStart, partNumber));
                frames.addAll(offsetFrameMaps(result.frames(), partStart, partNumber));
                attachedTranscript.addAll(offsetTranscriptNodes(result.transcript(), partStart, partNumber, attachedTranscript.size() + 1));
                trace.addAll(listValue(result.trace()));
                Map<String, Object> metadata = new LinkedHashMap<>(mapValue(result.metadata()));
                metadata.put("partNumber", partNumber);
                metadata.put("partStart", round3(partStart));
                partMetadata.add(metadata);
            }

            transactionTemplate.executeWithoutResult(status -> {
                CreatorShortVideo locked = findVideo(videoId, tenantId, userId);
                Map<String, Object> lockedIngestion = ingestionState(locked);
                Map<String, Object> videoAnalysis = new LinkedHashMap<>();
                videoAnalysis.put("status", scenes.isEmpty() ? "WARN" : "COMPLETED");
                videoAnalysis.put("source", "timeline_ingestion_part_by_part_scene_analysis");
                videoAnalysis.put("generatedAt", OffsetDateTime.now().toString());
                videoAnalysis.put("sceneCount", scenes.size());
                videoAnalysis.put("frameCount", frames.size());
                videoAnalysis.put("transcriptNodeCount", attachedTranscript.size());
                videoAnalysis.put("scenes", scenes);
                videoAnalysis.put("frames", frames);
                videoAnalysis.put("transcript", attachedTranscript.isEmpty() ? transcript : attachedTranscript);
                videoAnalysis.put("trace", trace);
                videoAnalysis.put("metadata", Map.of(
                        "parts", partMetadata,
                        "partCount", parts.size(),
                        "sequential", true
                ));
                lockedIngestion.put("videoAnalysis", videoAnalysis);
                putStageState(lockedIngestion, "videoAnalysis", scenes.isEmpty() ? "WARN" : "COMPLETED",
                        scenes.isEmpty() ? "Video analysis finished with no visual scenes." : "Video analysis completed sequentially by part.",
                        Map.of("sceneCount", scenes.size(), "frameCount", frames.size(), "partCount", parts.size()));
                addTrace(lockedIngestion, "VIDEO_ANALYSIS_COMPLETED", "Video analysis stage finished.", Map.of(
                        "sceneCount", scenes.size(),
                        "frameCount", frames.size(),
                        "partCount", parts.size()
                ));
                Map<String, Object> metadata = mapValue(locked.getMetadata());
                metadata.put("sceneTimelineSnapshot", videoAnalysis);
                locked.setMetadata(metadata);
                persistIngestion(locked, lockedIngestion, "TIMELINE_VIDEO_ANALYSIS_READY");
                videoRepository.saveAndFlush(locked);
            });
        } catch (Exception ex) {
            log.warn("Timeline video analysis stage failed videoId={} message={}", videoId, ex.getMessage());
            markStageFailed(videoId, tenantId, userId, "videoAnalysis", "TIMELINE_VIDEO_ANALYSIS_FAILED", safeMessage(ex));
        }
    }

    private void processStoryShots(UUID videoId, String tenantId, String userId) {
        try {
            CreatorShortVideo video = findVideo(videoId, tenantId, userId);
            Map<String, Object> ingestion = ingestionState(video);
            Map<String, Object> storyShots = buildStoryShots(video, ingestion);
            int storyCount = listValue(storyShots.get("stories")).size();
            int shotCount = listValue(storyShots.get("shotTimeline")).size();
            transactionTemplate.executeWithoutResult(status -> {
                CreatorShortVideo locked = findVideo(videoId, tenantId, userId);
                Map<String, Object> lockedIngestion = ingestionState(locked);
                lockedIngestion.put("storyShots", storyShots);
                putStageState(lockedIngestion, "storyShots", storyCount == 0 ? "WARN" : "COMPLETED",
                        storyCount == 0 ? "Story planning finished but no usable story windows were found." : "Story candidates and shot timeline generated.",
                        Map.of("storyCount", storyCount, "shotCount", shotCount));
                addTrace(lockedIngestion, "STORY_SHOTS_COMPLETED", "Story candidates, shots, captions, and attention metadata generated.", Map.of(
                        "storyCount", storyCount,
                        "shotCount", shotCount
                ));
                Map<String, Object> metadata = mapValue(locked.getMetadata());
                metadata.put("storyShotsSnapshot", storyShots);
                locked.setMetadata(metadata);
                persistIngestion(locked, lockedIngestion, "TIMELINE_STORY_SHOTS_READY");
                videoRepository.saveAndFlush(locked);
            });
        } catch (Exception ex) {
            log.warn("Timeline story/shot stage failed videoId={} message={}", videoId, ex.getMessage());
            markStageFailed(videoId, tenantId, userId, "storyShots", "TIMELINE_STORY_SHOTS_FAILED", safeMessage(ex));
        }
    }

    private List<Map<String, Object>> sceneClips(String sourceInput, int partNumber, double partStart, double duration, double sceneWindowSeconds) {
        List<Double> boundaries = sceneBoundaries(sourceInput, duration, sceneWindowSeconds);
        List<Map<String, Object>> scenes = new ArrayList<>();
        for (int index = 1; index < boundaries.size(); index++) {
            double localStart = Math.max(0.0, boundaries.get(index - 1));
            double localEnd = Math.max(localStart + 0.1, boundaries.get(index));
            double globalStart = partStart + localStart;
            double globalEnd = partStart + localEnd;
            Map<String, Object> clip = new LinkedHashMap<>();
            clip.put("clipId", "scene-p%03d-%03d".formatted(partNumber, index));
            clip.put("sceneId", "scene-p%03d-%03d".formatted(partNumber, index));
            clip.put("partNumber", partNumber);
            clip.put("start", round3(globalStart));
            clip.put("end", round3(globalEnd));
            clip.put("localStart", round3(localStart));
            clip.put("localEnd", round3(localEnd));
            clip.put("layout", "FULL_FOOTAGE");
            clip.put("purpose", index == 1 && partNumber == 1 ? "HOOK" : "SCENE");
            clip.put("label", "Scene " + partNumber + "." + index);
            clip.put("sourceUrl", sourceInput);
            clip.put("editable", true);
            clip.put("regeneratable", true);
            clip.put("source", "ffmpeg_scene_detection");
            scenes.add(clip);
        }
        return scenes;
    }

    private List<Map<String, Object>> thumbnailClips(
            CreatorShortVideo video,
            Map<String, Object> partState,
            String sourceInput,
            int partNumber,
            double partStart,
            double duration,
            double requestedInterval,
            MediaInfo mediaInfo
    ) throws IOException {
        List<Map<String, Object>> thumbnails = new ArrayList<>();
        double interval = Math.max(requestedInterval, duration / Math.max(1, MAX_THUMBNAILS_PER_PART));
        Path frameDir = Files.createTempDirectory("creator-timeline-thumbnails-" + video.getId() + "-" + partNumber + "-");
        try {
            int index = 0;
            for (double localTime = 0.0; localTime < duration - 0.05 && index < MAX_THUMBNAILS_PER_PART; localTime += interval) {
                double timestamp = Math.min(Math.max(0.0, localTime), Math.max(0.0, duration - 0.05));
                Path framePath = frameDir.resolve("thumb-%04d.jpg".formatted(index + 1));
                run(List.of(
                        "ffmpeg",
                        "-hide_banner",
                        "-y",
                        "-ss", format(timestamp),
                        "-i", sourceInput,
                        "-frames:v", "1",
                        "-vf", "scale=240:-2:force_original_aspect_ratio=decrease",
                        "-q:v", "6",
                        framePath.toString()
                ));
                if (!Files.isRegularFile(framePath) || Files.size(framePath) == 0) {
                    index++;
                    continue;
                }
                String thumbnailId = "thumb-p%03d-%04d".formatted(partNumber, index + 1);
                String objectKey = thumbnailObjectKey(video.getTenantId(), video.getUserId(), video.getId(), partNumber, thumbnailId);
                AssetStorageService.StoredObject stored = assetStorageService.uploadCreatorAssetFromPath(objectKey, framePath, "image/jpeg", SIGNED_URL_TTL);
                CreatorAsset thumbAsset = assetRepository.saveAndFlush(CreatorAsset.builder()
                        .id(UUID.randomUUID())
                        .tenantId(video.getTenantId())
                        .userId(video.getUserId())
                        .projectId(video.getProjectId())
                        .assetType(ASSET_TYPE_THUMBNAIL)
                        .bucket(stored.bucket())
                        .objectKey(stored.objectKey())
                        .contentType(stored.contentType())
                        .sizeBytes(stored.sizeBytes())
                        .publicUrl(stored.signedUrl())
                        .metadata(Map.of(
                                "videoId", video.getId().toString(),
                                "partNumber", partNumber,
                                "timestampSeconds", round3(partStart + timestamp),
                                "timelineIngestion", true
                        ))
                        .build());

                Map<String, Object> clip = new LinkedHashMap<>();
                clip.put("clipId", thumbnailId);
                clip.put("thumbnailId", thumbnailId);
                clip.put("assetId", thumbAsset.getId().toString());
                clip.put("partNumber", partNumber);
                clip.put("start", round3(partStart + timestamp));
                clip.put("end", round3(Math.min(partStart + duration, partStart + timestamp + interval)));
                clip.put("localTimestampSeconds", round3(timestamp));
                clip.put("timestampSeconds", round3(partStart + timestamp));
                clip.put("thumbnailUrl", stored.signedUrl());
                clip.put("sourcePartAssetId", stringValue(partState.get("sourceAssetId")));
                clip.put("sourceUrl", stringValue(partState.get("url")));
                clip.put("layout", "FULL_FOOTAGE");
                clip.put("purpose", "VIDEO_THUMBNAIL");
                clip.put("editable", true);
                clip.put("regeneratable", false);
                clip.put("width", mediaInfo.width());
                clip.put("height", mediaInfo.height());
                clip.put("aspectRatio", mediaInfo.aspectRatio());
                thumbnails.add(clip);
                index++;
            }
        } finally {
            deleteQuietly(frameDir);
        }
        return thumbnails;
    }

    private List<Double> sceneBoundaries(String sourceInput, double duration, double sceneWindowSeconds) {
        List<Double> detected = new ArrayList<>();
        detected.add(0.0);
        try {
            String output = run(List.of(
                    "ffmpeg",
                    "-hide_banner",
                    "-i", sourceInput,
                    "-filter:v", "select=gt(scene\\," + SCENE_THRESHOLD + "),showinfo",
                    "-vsync", "vfr",
                    "-frames:v", String.valueOf(MAX_SCENE_DETECTION_FRAMES),
                    "-f", "null",
                    "-"
            ));
            Pattern pattern = Pattern.compile("pts_time:([0-9]+(?:\\.[0-9]+)?)");
            Matcher matcher = pattern.matcher(output);
            while (matcher.find()) {
                double value = doubleValue(matcher.group(1), -1.0);
                if (value > 1.0 && value < duration - 0.25) {
                    detected.add(round3(value));
                }
            }
        } catch (RuntimeException ex) {
            log.info("Timeline ingestion scene detection fell back to uniform windows: {}", ex.getMessage());
        }
        if (duration > 0) {
            detected.add(round3(duration));
        }
        List<Double> compacted = compactBoundaries(detected, duration);
        if (compacted.size() > 2) {
            return compacted;
        }
        return uniformBoundaries(duration, sceneWindowSeconds);
    }

    private MediaInfo probe(String sourceInput) {
        try {
            String output = run(List.of(
                    "ffprobe",
                    "-v", "error",
                    "-select_streams", "v:0",
                    "-show_entries", "stream=width,height",
                    "-show_entries", "format=duration",
                    "-of", "default=noprint_wrappers=1:nokey=1",
                    sourceInput
            ));
            List<String> lines = output.lines().map(String::trim).filter(line -> !line.isBlank()).toList();
            int width = lines.size() > 0 ? intValue(lines.get(0), 0) : 0;
            int height = lines.size() > 1 ? intValue(lines.get(1), 0) : 0;
            double duration = lines.size() > 2 ? doubleValue(lines.get(2), 0.0) : 0.0;
            return new MediaInfo(width, height, duration);
        } catch (RuntimeException ex) {
            return new MediaInfo(0, 0, 0.0);
        }
    }

    private String run(List<String> command) {
        Process process = null;
        try {
            process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(FFMPEG_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!finished) {
                process.destroyForcibly();
                throw new IllegalStateException("FFmpeg timeline ingestion timed out.");
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException("FFmpeg timeline ingestion failed: " + tail(output, 2400));
            }
            return output;
        } catch (IOException ex) {
            throw new IllegalStateException("FFmpeg/ffprobe is not available for timeline ingestion.", ex);
        } catch (InterruptedException ex) {
            if (process != null) {
                process.destroyForcibly();
            }
            Thread.currentThread().interrupt();
            throw new IllegalStateException("FFmpeg timeline ingestion was interrupted.", ex);
        }
    }

    private void markPartStatus(UUID videoId, String tenantId, String userId, int partNumber, String status, Map<String, Object> details) {
        CreatorShortVideo video = findVideo(videoId, tenantId, userId);
        Map<String, Object> ingestion = ingestionState(video);
        Map<String, Object> part = partByNumber(sourceParts(ingestion), partNumber);
        if (part == null) {
            return;
        }
        part.put("status", status);
        if (details != null && !details.isEmpty()) {
            part.putAll(details);
        }
        addTrace(ingestion, "PART_" + status, "Part " + partNumber + " status changed to " + status + ".", Map.of("partNumber", partNumber));
        updateAggregateStatus(ingestion);
        if ("FAILED".equals(status)) {
            putStageState(ingestion, "fabricTimeline", "FAILED", defaultString(details == null ? null : details.get("error"), "Fabric timeline extraction failed."), Map.of(
                    "partNumber", partNumber
            ));
        } else if ("ANALYZING".equals(status) || "QUEUED".equals(status)) {
            putStageState(ingestion, "fabricTimeline", "RUNNING", "FFmpeg is extracting scenes and thumbnails part-by-part.", Map.of(
                    "partNumber", partNumber
            ));
        }
        persistIngestion(video, ingestion, "FAILED".equals(status) ? "TIMELINE_PART_FAILED" : "TIMELINE_RENDERING");
        videoRepository.saveAndFlush(video);
    }

    private void persistIngestion(CreatorShortVideo video, Map<String, Object> ingestion, String status) {
        ingestion.put("updatedAt", OffsetDateTime.now().toString());
        ingestion.put("timelineProject", timelineProject(video.getId(), defaultString(video.getTitle(), "Timeline ingestion"), ingestion));
        Map<String, Object> metadata = mapValue(video.getMetadata());
        metadata.put("timelineIngestion", ingestion);
        metadata.put("timelineIngestionProject", ingestion.get("timelineProject"));
        video.setMetadata(metadata);
        video.setStatus(status);
    }

    private void updateAggregateStatus(Map<String, Object> ingestion) {
        List<Map<String, Object>> parts = sourceParts(ingestion);
        int received = parts.size();
        int processed = 0;
        int failed = 0;
        int running = 0;
        int uploaded = 0;
        for (Map<String, Object> part : parts) {
            String status = stringValue(part.get("status"));
            if ("PROCESSED".equalsIgnoreCase(status)) {
                processed++;
            }
            if ("FAILED".equalsIgnoreCase(status)) {
                failed++;
            }
            if ("QUEUED".equalsIgnoreCase(status) || "ANALYZING".equalsIgnoreCase(status)) {
                running++;
            }
            if ("UPLOADED".equalsIgnoreCase(status)) {
                uploaded++;
            }
        }
        int expected = Math.max(1, intValue(ingestion.get("expectedParts"), received == 0 ? 1 : received));
        ingestion.put("receivedParts", received);
        ingestion.put("processedParts", processed);
        ingestion.put("failedParts", failed);
        if (failed > 0) {
            ingestion.put("status", "PARTIAL_WITH_ERRORS");
            return;
        }
        boolean finalReceived = booleanValue(ingestion.get("finalPartReceived"), false);
        boolean completeRequested = booleanValue(ingestion.get("completeRequested"), false);
        if (processed > 0 && processed == received && (finalReceived || completeRequested || received >= expected)) {
            ingestion.put("status", "READY");
        } else if (running > 0) {
            ingestion.put("status", "RENDERING");
        } else if (processed > 0) {
            ingestion.put("status", "PARTIAL_READY");
        } else if (uploaded > 0 || received > 0) {
            ingestion.put("status", "UPLOADED");
        } else {
            ingestion.put("status", "READY_FOR_UPLOAD");
        }
    }

    private Map<String, Object> timelineProject(UUID videoId, String title, Map<String, Object> ingestion) {
        List<Map<String, Object>> parts = sourceParts(ingestion);
        List<Map<String, Object>> sceneClips = new ArrayList<>();
        List<Map<String, Object>> thumbnailClips = new ArrayList<>();
        List<Map<String, Object>> assets = new ArrayList<>();
        for (Map<String, Object> part : parts) {
            List<Map<String, Object>> partScenes = listValue(part.get("sceneClips"));
            List<Map<String, Object>> partThumbnails = listValue(part.get("thumbnailClips"));
            sceneClips.addAll(partScenes.isEmpty() ? List.of(sourcePartSceneClip(part)) : partScenes);
            thumbnailClips.addAll(partThumbnails.isEmpty() ? List.of(sourcePartThumbnailClip(part)) : partThumbnails);
            Map<String, Object> partAsset = new LinkedHashMap<>();
            partAsset.put("assetId", part.get("sourceAssetId"));
            partAsset.put("type", "source_part");
            partAsset.put("partNumber", part.get("partNumber"));
            partAsset.put("url", part.get("url"));
            partAsset.put("start", part.get("start"));
            partAsset.put("end", part.get("end"));
            partAsset.put("role", "master_video_segment");
            assets.add(partAsset);
            for (Map<String, Object> thumbnail : listValue(part.get("thumbnailClips"))) {
                Map<String, Object> thumbnailAsset = new LinkedHashMap<>();
                thumbnailAsset.put("assetId", thumbnail.get("assetId"));
                thumbnailAsset.put("type", "thumbnail");
                thumbnailAsset.put("partNumber", thumbnail.get("partNumber"));
                thumbnailAsset.put("url", thumbnail.get("thumbnailUrl"));
                thumbnailAsset.put("timestampSeconds", thumbnail.get("timestampSeconds"));
                thumbnailAsset.put("role", "timeline_thumbnail");
                assets.add(thumbnailAsset);
            }
        }
        sceneClips.sort(Comparator.comparingDouble(item -> doubleValue(item.get("start"), 0.0)));
        thumbnailClips.sort(Comparator.comparingDouble(item -> doubleValue(item.get("start"), 0.0)));

        Map<String, Object> project = new LinkedHashMap<>();
        project.put("project", Map.of(
                "projectId", videoId.toString(),
                "title", title,
                "mode", "TIMELINE_INGESTION",
                "source", "backend_part_by_part_ffmpeg",
                "updatedAt", OffsetDateTime.now().toString()
        ));
        project.put("masterVideo", masterVideo(parts));
        project.put("stageStates", stageStates(ingestion));
        project.put("storyShots", mapValue(ingestion.get("storyShots")));
        project.put("workflow", Map.of(
                "mode", "UPLOAD_THEN_BROWSER_EDIT_THEN_AI",
                "stageOrder", List.of("upload", "fabricTimeline", "transcriptTimeline", "videoAnalysis", "storyShots"),
                "backendFullPipelineStarted", false,
                "clientSideRenderPreferred", true
        ));
        project.put("tracks", List.of(
                Map.of(
                        "trackId", "ai-scenes",
                        "type", "AI_SCENES",
                        "label", "AI scenes / shots",
                        "clips", sceneClips
                ),
                Map.of(
                        "trackId", "actual-video-thumbnails",
                        "type", "VIDEO_THUMBNAILS",
                        "label", "Actual video thumbnails",
                        "clips", thumbnailClips
                )
        ));
        project.put("assets", assets);
        project.put("interactionModel", Map.of(
                "thumbnailClick", Map.of(
                        "seekTo", "timestampSeconds",
                        "openPropertiesPanel", true
                ),
                "propertiesPanelActions", List.of("split", "trim", "delete", "zoom", "add_captions")
        ));
        project.put("timelineVersions", List.of(Map.of(
                "version", 1,
                "createdBy", "BACKEND_FFMPEG",
                "description", "Part-by-part timeline ingestion"
        )));
        return project;
    }

    private Map<String, Object> sourcePartSceneClip(Map<String, Object> part) {
        int partNumber = intValue(part.get("partNumber"), 1);
        double start = doubleValue(part.get("start"), Math.max(0, partNumber - 1) * DEFAULT_PART_DURATION_SECONDS);
        double end = Math.max(start + 0.1, doubleValue(part.get("end"), start + doubleValue(part.get("declaredDurationSeconds"), DEFAULT_PART_DURATION_SECONDS)));
        Map<String, Object> clip = new LinkedHashMap<>();
        clip.put("clipId", "source-part-%03d".formatted(partNumber));
        clip.put("sceneId", "source-part-%03d".formatted(partNumber));
        clip.put("partNumber", partNumber);
        clip.put("start", round3(start));
        clip.put("end", round3(end));
        clip.put("localStart", 0.0);
        clip.put("localEnd", round3(end - start));
        clip.put("layout", "FULL_FOOTAGE");
        clip.put("purpose", partNumber == 1 ? "HOOK" : "SOURCE_PART");
        clip.put("label", "Uploaded part " + partNumber);
        clip.put("sourceUrl", stringValue(part.get("url")));
        clip.put("editable", true);
        clip.put("regeneratable", true);
        clip.put("source", "upload_placeholder_until_fabric_timeline");
        return clip;
    }

    private Map<String, Object> sourcePartThumbnailClip(Map<String, Object> part) {
        int partNumber = intValue(part.get("partNumber"), 1);
        double start = doubleValue(part.get("start"), Math.max(0, partNumber - 1) * DEFAULT_PART_DURATION_SECONDS);
        double end = Math.max(start + 0.1, doubleValue(part.get("end"), start + doubleValue(part.get("declaredDurationSeconds"), DEFAULT_PART_DURATION_SECONDS)));
        Map<String, Object> clip = new LinkedHashMap<>();
        clip.put("clipId", "uploaded-part-%03d".formatted(partNumber));
        clip.put("thumbnailId", "uploaded-part-%03d".formatted(partNumber));
        clip.put("partNumber", partNumber);
        clip.put("start", round3(start));
        clip.put("end", round3(end));
        clip.put("localTimestampSeconds", 0.0);
        clip.put("timestampSeconds", round3(start));
        clip.put("thumbnailUrl", "");
        clip.put("sourcePartAssetId", stringValue(part.get("sourceAssetId")));
        clip.put("sourceUrl", stringValue(part.get("url")));
        clip.put("layout", "FULL_FOOTAGE");
        clip.put("purpose", "UPLOADED_VIDEO_PART");
        clip.put("label", "Uploaded part " + partNumber);
        clip.put("editable", true);
        clip.put("regeneratable", false);
        return clip;
    }

    private Map<String, Object> masterVideo(List<Map<String, Object>> parts) {
        List<Map<String, Object>> sourceParts = new ArrayList<>();
        double duration = 0.0;
        for (Map<String, Object> part : parts) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("partNumber", part.get("partNumber"));
            item.put("sourceAssetId", part.get("sourceAssetId"));
            item.put("url", part.get("url"));
            item.put("start", part.get("start"));
            item.put("end", part.get("end"));
            item.put("status", part.get("status"));
            sourceParts.add(item);
            duration = Math.max(duration, doubleValue(part.get("end"), 0.0));
        }
        Map<String, Object> master = new LinkedHashMap<>();
        master.put("source", "backend_uploaded_parts");
        master.put("durationSeconds", round3(duration));
        master.put("sourceParts", sourceParts);
        return master;
    }

    private Map<String, Object> buildStoryShots(CreatorShortVideo video, Map<String, Object> ingestion) {
        Map<String, Object> videoAnalysis = mapValue(ingestion.get("videoAnalysis"));
        List<Map<String, Object>> transcript = listValue(videoAnalysis.get("transcript"));
        if (transcript.isEmpty()) {
            transcript = transcriptNodesForAnalysis(video, ingestion);
        }
        List<Map<String, Object>> scenes = listValue(videoAnalysis.get("scenes"));
        if (scenes.isEmpty()) {
            scenes = timelineSceneClipsFromParts(ingestion);
        }
        List<Map<String, Object>> frames = listValue(videoAnalysis.get("frames"));
        List<Map<String, Object>> thumbnails = timelineThumbnailClipsFromParts(ingestion);
        List<Map<String, Object>> windows = storyWindows(transcript, scenes);

        List<Map<String, Object>> stories = new ArrayList<>();
        List<Map<String, Object>> allShots = new ArrayList<>();
        List<Map<String, Object>> allCaptions = new ArrayList<>();
        List<Map<String, Object>> allFrameSeconds = new ArrayList<>();
        int storyIndex = 1;
        for (Map<String, Object> window : windows) {
            String storyId = "story_%02d".formatted(storyIndex);
            double start = doubleValue(window.get("start"), 0.0);
            double end = Math.max(start + 1.0, doubleValue(window.get("end"), start + 1.0));
            List<Map<String, Object>> storyTranscript = transcriptForRange(transcript, start, end);
            List<Map<String, Object>> storyScenes = scenesForRange(scenes, start, end);
            List<Map<String, Object>> captions = captionsForRange(storyId, storyTranscript, start, end);
            List<Map<String, Object>> shots = shotsForStory(storyId, start, end, storyTranscript, storyScenes, frames, thumbnails);
            List<Map<String, Object>> frameSeconds = frameSecondTimelineForStory(storyId, start, end, shots, captions, frames, thumbnails);
            Map<String, Object> attention = attentionForStory(start, end, storyTranscript, shots);
            List<Map<String, Object>> retention = retentionOpportunitiesForStory(start, end, storyTranscript, shots);

            Map<String, Object> story = new LinkedHashMap<>();
            story.put("storyId", storyId);
            story.put("rank", storyIndex);
            story.put("title", storyTitle(video, storyTranscript, storyIndex));
            story.put("hook", storyHook(storyTranscript, storyScenes));
            story.put("summary", storySummary(storyTranscript, storyScenes));
            story.put("storyText", storyText(storyTranscript));
            story.put("whatItSays", storyText(storyTranscript));
            story.put("start", round3(start));
            story.put("end", round3(end));
            story.put("durationSeconds", round3(end - start));
            story.put("source", "timeline_ingestion_story_shots");
            story.put("attentionScore", attention.get("score"));
            story.put("attention", attention);
            story.put("attentionSpan", attention);
            story.put("retentionOpportunities", retention);
            story.put("captions", captions);
            story.put("captionTimeline", captions);
            story.put("shots", shots);
            story.put("shotsToTake", shots);
            story.put("frameSecondTimeline", frameSeconds);
            story.put("editable", true);
            story.put("regeneratable", true);
            stories.add(story);
            allShots.addAll(shots);
            allCaptions.addAll(captions);
            allFrameSeconds.addAll(frameSeconds);
            storyIndex++;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", stories.isEmpty() ? "WARN" : "COMPLETED");
        result.put("source", "timeline_ingestion_story_shots");
        result.put("generatedAt", OffsetDateTime.now().toString());
        result.put("storyCount", stories.size());
        result.put("shotCount", allShots.size());
        result.put("captionCount", allCaptions.size());
        result.put("frameSecondCount", allFrameSeconds.size());
        result.put("stories", stories);
        result.put("shotTimeline", allShots);
        result.put("shotsToTake", allShots);
        result.put("captions", allCaptions);
        result.put("captionTimeline", allCaptions);
        result.put("frameSecondTimeline", allFrameSeconds);
        result.put("attention", aggregateAttention(stories));
        result.put("attentionSpan", aggregateAttention(stories));
        result.put("contract", Map.of(
                "renderingRequired", false,
                "sourceOfTruth", "master_video_plus_timeline_json",
                "timelineGranularity", "seconds",
                "editableInBrowser", true
        ));
        return result;
    }

    private List<Map<String, Object>> storyWindows(List<Map<String, Object>> transcript, List<Map<String, Object>> scenes) {
        List<Map<String, Object>> candidates = new ArrayList<>();
        List<Map<String, Object>> sortedTranscript = sortedByStart(transcript);
        for (int index = 0; index < sortedTranscript.size(); index++) {
            Map<String, Object> first = sortedTranscript.get(index);
            double start = doubleValue(first.get("start"), 0.0);
            double end = start;
            StringBuilder text = new StringBuilder();
            int nodeCount = 0;
            for (int inner = index; inner < sortedTranscript.size() && nodeCount < 10; inner++) {
                Map<String, Object> node = sortedTranscript.get(inner);
                double nodeEnd = doubleValue(node.get("end"), doubleValue(node.get("start"), start) + 1.0);
                if (nodeEnd - start > 75.0) {
                    break;
                }
                text.append(' ').append(captionText(node));
                end = Math.max(end, nodeEnd);
                nodeCount++;
                if (end - start >= 24.0 && nodeCount >= 3) {
                    break;
                }
            }
            if (end - start < 4.0) {
                continue;
            }
            Map<String, Object> candidate = new LinkedHashMap<>();
            candidate.put("start", round3(start));
            candidate.put("end", round3(end));
            candidate.put("score", attentionScore(text.toString()) + Math.min(0.2, scenesForRange(scenes, start, end).size() * 0.03));
            candidates.add(candidate);
        }
        if (candidates.isEmpty()) {
            List<Map<String, Object>> sortedScenes = sortedByStart(scenes);
            for (int index = 0; index < sortedScenes.size() && index < 12; index++) {
                Map<String, Object> scene = sortedScenes.get(index);
                double start = doubleValue(scene.get("start"), 0.0);
                double end = Math.max(start + 4.0, Math.min(start + 45.0, doubleValue(scene.get("end"), start + 30.0)));
                Map<String, Object> candidate = new LinkedHashMap<>();
                candidate.put("start", round3(start));
                candidate.put("end", round3(end));
                candidate.put("score", 0.48 + (index == 0 ? 0.1 : 0.0));
                candidates.add(candidate);
            }
        }
        candidates.sort((left, right) -> Double.compare(doubleValue(right.get("score"), 0.0), doubleValue(left.get("score"), 0.0)));
        List<Map<String, Object>> selected = new ArrayList<>();
        for (Map<String, Object> candidate : candidates) {
            double start = doubleValue(candidate.get("start"), 0.0);
            double end = doubleValue(candidate.get("end"), start);
            boolean overlapsExisting = false;
            for (Map<String, Object> existing : selected) {
                if (overlaps(start, end, doubleValue(existing.get("start"), 0.0), doubleValue(existing.get("end"), 0.0), 8.0)) {
                    overlapsExisting = true;
                    break;
                }
            }
            if (!overlapsExisting) {
                selected.add(candidate);
            }
            if (selected.size() >= 10) {
                break;
            }
        }
        selected.sort(Comparator.comparingDouble(item -> doubleValue(item.get("start"), 0.0)));
        return selected;
    }

    private List<Map<String, Object>> shotsForStory(
            String storyId,
            double storyStart,
            double storyEnd,
            List<Map<String, Object>> transcript,
            List<Map<String, Object>> scenes,
            List<Map<String, Object>> frames,
            List<Map<String, Object>> thumbnails
    ) {
        List<Map<String, Object>> shots = new ArrayList<>();
        List<Map<String, Object>> source = scenes.isEmpty() ? transcript : scenes;
        int shotIndex = 1;
        for (Map<String, Object> item : sortedByStart(source)) {
            double start = clampDouble(doubleValue(item.get("start"), storyStart), storyStart, storyEnd);
            double end = clampDouble(doubleValue(item.get("end"), start + 3.0), start + 0.5, storyEnd);
            for (double cursor = start; cursor < end - 0.1 && shotIndex <= 20; cursor += 4.0) {
                double shotEnd = Math.min(end, cursor + 4.0);
                Map<String, Object> frame = nearestTimedItem(frames, cursor);
                Map<String, Object> thumbnail = nearestTimedItem(thumbnails, cursor);
                String purpose = shotPurpose(shotIndex, cursor, storyStart, storyEnd);
                Map<String, Object> shot = new LinkedHashMap<>();
                shot.put("shotId", "%s_shot_%02d".formatted(storyId, shotIndex));
                shot.put("storyId", storyId);
                shot.put("start", round3(cursor));
                shot.put("end", round3(shotEnd));
                shot.put("durationSeconds", round3(shotEnd - cursor));
                shot.put("layout", layoutForShot(shotIndex, purpose));
                shot.put("purpose", purpose);
                shot.put("caption", bestCaptionForRange(transcript, cursor, shotEnd));
                shot.put("sceneId", firstNonBlank(item.get("sceneId"), item.get("id")));
                shot.put("frameId", firstNonBlank(frame.get("id"), thumbnail.get("thumbnailId")));
                shot.put("thumbnailUrl", firstNonBlank(thumbnail.get("thumbnailUrl"), thumbnail.get("url")));
                shot.put("sourceUrl", firstNonBlank(item.get("sourceUrl"), thumbnail.get("sourceUrl")));
                shot.put("attentionScore", round3(attentionScore(String.join(" ", String.valueOf(shot.get("caption")), purpose))));
                shot.put("retentionEffect", retentionEffectForShot(shotIndex, purpose));
                shot.put("editable", true);
                shot.put("regeneratable", true);
                shots.add(shot);
                shotIndex++;
            }
        }
        if (shots.isEmpty()) {
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("shotId", storyId + "_shot_01");
            fallback.put("storyId", storyId);
            fallback.put("start", round3(storyStart));
            fallback.put("end", round3(storyEnd));
            fallback.put("durationSeconds", round3(storyEnd - storyStart));
            fallback.put("layout", "FULL_FOOTAGE");
            fallback.put("purpose", "HOOK");
            fallback.put("caption", "Source story segment");
            fallback.put("attentionScore", 0.45);
            fallback.put("retentionEffect", "PUNCH_ZOOM");
            fallback.put("editable", true);
            fallback.put("regeneratable", true);
            shots.add(fallback);
        }
        return shots;
    }

    private List<Map<String, Object>> frameSecondTimelineForStory(
            String storyId,
            double start,
            double end,
            List<Map<String, Object>> shots,
            List<Map<String, Object>> captions,
            List<Map<String, Object>> frames,
            List<Map<String, Object>> thumbnails
    ) {
        List<Map<String, Object>> timeline = new ArrayList<>();
        int firstSecond = (int) Math.floor(start);
        int lastSecond = (int) Math.min(Math.ceil(end), firstSecond + 90);
        for (int second = firstSecond; second < lastSecond; second++) {
            double timestamp = second;
            Map<String, Object> shot = itemForTimestamp(shots, timestamp);
            Map<String, Object> caption = itemForTimestamp(captions, timestamp);
            Map<String, Object> frame = nearestTimedItem(frames, timestamp);
            Map<String, Object> thumbnail = nearestTimedItem(thumbnails, timestamp);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("storyId", storyId);
            row.put("second", second - firstSecond);
            row.put("timestampSeconds", round3(timestamp));
            row.put("shotId", shot.getOrDefault("shotId", ""));
            row.put("frameId", firstNonBlank(frame.get("id"), thumbnail.get("thumbnailId"), shot.get("frameId")));
            row.put("thumbnailUrl", firstNonBlank(thumbnail.get("thumbnailUrl"), shot.get("thumbnailUrl")));
            row.put("captionId", caption.getOrDefault("captionId", ""));
            row.put("caption", firstNonBlank(caption.get("text"), shot.get("caption")));
            row.put("layout", firstNonBlank(shot.get("layout"), "FULL_FOOTAGE"));
            row.put("attentionCue", attentionCueForSecond(second - firstSecond, firstNonBlank(caption.get("text"), shot.get("caption"))));
            timeline.add(row);
        }
        return timeline;
    }

    private List<Map<String, Object>> captionsForRange(String storyId, List<Map<String, Object>> transcript, double storyStart, double storyEnd) {
        List<Map<String, Object>> captions = new ArrayList<>();
        int index = 1;
        for (Map<String, Object> node : sortedByStart(transcript)) {
            String text = captionText(node);
            if (text.isBlank()) {
                continue;
            }
            double start = clampDouble(doubleValue(node.get("start"), storyStart), storyStart, storyEnd);
            double end = clampDouble(doubleValue(node.get("end"), start + 1.5), start + 0.2, storyEnd);
            Map<String, Object> caption = new LinkedHashMap<>();
            caption.put("captionId", "%s_caption_%03d".formatted(storyId, index));
            caption.put("storyId", storyId);
            caption.put("start", round3(start));
            caption.put("end", round3(end));
            caption.put("text", text);
            caption.put("speaker", defaultString(node.get("speaker"), "Speaker"));
            caption.put("partNumber", node.get("partNumber"));
            caption.put("editable", true);
            captions.add(caption);
            index++;
        }
        return captions;
    }

    private Map<String, Object> attentionForStory(double start, double end, List<Map<String, Object>> transcript, List<Map<String, Object>> shots) {
        String text = transcript.stream().map(this::captionText).reduce("", (left, right) -> left + " " + right);
        double score = attentionScore(text) + Math.min(0.2, shots.size() * 0.01);
        List<Map<String, Object>> curve = new ArrayList<>();
        for (double cursor = start; cursor < end; cursor += 5.0) {
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("start", round3(cursor));
            point.put("end", round3(Math.min(end, cursor + 5.0)));
            point.put("score", round3(Math.max(0.25, Math.min(0.95, score - Math.max(0.0, cursor - start - 18.0) * 0.006))));
            point.put("cue", attentionCueForSecond((int) Math.round(cursor - start), bestCaptionForRange(transcript, cursor, Math.min(end, cursor + 5.0))));
            curve.add(point);
        }
        Map<String, Object> attention = new LinkedHashMap<>();
        attention.put("score", round3(Math.min(0.98, score)));
        attention.put("hookWindowSeconds", 3);
        attention.put("visualChangeEverySeconds", shots.size() > 1 ? 4 : 6);
        attention.put("captionDensity", transcript.isEmpty() ? "LOW" : transcript.size() >= 5 ? "HIGH" : "MEDIUM");
        attention.put("curve", curve);
        attention.put("dropRisk", dropRiskLabel(score, shots.size(), end - start));
        attention.put("recommendedEffects", recommendedEffectsForAttention(score));
        return attention;
    }

    private List<Map<String, Object>> retentionOpportunitiesForStory(double start, double end, List<Map<String, Object>> transcript, List<Map<String, Object>> shots) {
        List<Map<String, Object>> items = new ArrayList<>();
        if (end - start > 18.0) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("start", round3(start + 8.0));
            item.put("end", round3(Math.min(end, start + 12.0)));
            item.put("reason", "Mid-story attention reset");
            item.put("suggestedEffect", "PUNCH_ZOOM");
            items.add(item);
        }
        if (shots.size() < Math.max(2, (int) ((end - start) / 6.0))) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("start", round3(start));
            item.put("end", round3(end));
            item.put("reason", "Low visual variety for the selected duration");
            item.put("suggestedEffect", "SWIPE_TRANSITION");
            items.add(item);
        }
        for (Map<String, Object> node : transcript) {
            String text = captionText(node);
            if (attentionScore(text) > 0.65) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("start", node.get("start"));
                item.put("end", node.get("end"));
                item.put("reason", "High-attention claim or reveal");
                item.put("suggestedEffect", "QUOTE_POP");
                items.add(item);
            }
            if (items.size() >= 6) {
                break;
            }
        }
        return items;
    }

    private Map<String, Object> aggregateAttention(List<Map<String, Object>> stories) {
        double total = 0.0;
        double peak = 0.0;
        String peakStory = "";
        for (Map<String, Object> story : stories) {
            double score = doubleValue(story.get("attentionScore"), 0.0);
            total += score;
            if (score > peak) {
                peak = score;
                peakStory = stringValue(story.get("storyId"));
            }
        }
        Map<String, Object> attention = new LinkedHashMap<>();
        attention.put("averageScore", stories.isEmpty() ? 0.0 : round3(total / stories.size()));
        attention.put("peakScore", round3(peak));
        attention.put("peakStoryId", peakStory);
        attention.put("strategy", "Hook in first 3 seconds, visual change every 2-5 seconds, caption every speech beat.");
        attention.put("storyCount", stories.size());
        return attention;
    }

    private List<Map<String, Object>> timelineSceneClipsFromParts(Map<String, Object> ingestion) {
        List<Map<String, Object>> scenes = new ArrayList<>();
        for (Map<String, Object> part : sourceParts(ingestion)) {
            scenes.addAll(listValue(part.get("sceneClips")));
        }
        if (scenes.isEmpty()) {
            for (Map<String, Object> part : sourceParts(ingestion)) {
                scenes.add(sourcePartSceneClip(part));
            }
        }
        return sortedByStart(scenes);
    }

    private List<Map<String, Object>> timelineThumbnailClipsFromParts(Map<String, Object> ingestion) {
        List<Map<String, Object>> thumbnails = new ArrayList<>();
        for (Map<String, Object> part : sourceParts(ingestion)) {
            thumbnails.addAll(listValue(part.get("thumbnailClips")));
        }
        return sortedByStart(thumbnails);
    }

    private List<Map<String, Object>> sortedByStart(List<Map<String, Object>> items) {
        return listValue(items).stream()
                .sorted(Comparator.comparingDouble(item -> doubleValue(item.get("start"), doubleValue(item.get("timestampSeconds"), 0.0))))
                .toList();
    }

    private List<Map<String, Object>> transcriptForRange(List<Map<String, Object>> transcript, double start, double end) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> node : sortedByStart(transcript)) {
            if (rangeOverlaps(node, start, end, 0.0)) {
                result.add(node);
            }
        }
        return result;
    }

    private List<Map<String, Object>> scenesForRange(List<Map<String, Object>> scenes, double start, double end) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> scene : sortedByStart(scenes)) {
            if (rangeOverlaps(scene, start, end, 0.0)) {
                result.add(scene);
            }
        }
        return result;
    }

    private boolean rangeOverlaps(Map<String, Object> item, double start, double end, double tolerance) {
        double itemStart = doubleValue(item.get("start"), doubleValue(item.get("timestampSeconds"), 0.0));
        double itemEnd = doubleValue(item.get("end"), itemStart + 0.5);
        return overlaps(itemStart, itemEnd, start, end, tolerance);
    }

    private boolean overlaps(double leftStart, double leftEnd, double rightStart, double rightEnd, double tolerance) {
        return leftStart <= rightEnd + tolerance && leftEnd + tolerance >= rightStart;
    }

    private String storyTitle(CreatorShortVideo video, List<Map<String, Object>> transcript, int storyIndex) {
        String hook = storyHook(transcript, List.of());
        if (!hook.isBlank()) {
            return truncateTitle(hook);
        }
        return defaultString(video == null ? null : video.getTitle(), "Story") + " #" + storyIndex;
    }

    private String storyHook(List<Map<String, Object>> transcript, List<Map<String, Object>> scenes) {
        for (Map<String, Object> node : transcript) {
            String text = captionText(node);
            if (!text.isBlank()) {
                return text;
            }
        }
        for (Map<String, Object> scene : scenes) {
            String label = defaultString(firstNonBlank(scene.get("label"), scene.get("summary"), scene.get("id")), "");
            if (!label.isBlank()) {
                return label;
            }
        }
        return "Selected story moment";
    }

    private String storySummary(List<Map<String, Object>> transcript, List<Map<String, Object>> scenes) {
        StringBuilder text = new StringBuilder();
        for (Map<String, Object> node : transcript) {
            String line = captionText(node);
            if (!line.isBlank()) {
                text.append(line).append(' ');
            }
            if (text.length() > 240) {
                break;
            }
        }
        if (text.isEmpty() && !scenes.isEmpty()) {
            text.append(defaultString(firstNonBlank(scenes.get(0).get("label"), scenes.get(0).get("id")), "Visual scene"));
        }
        String value = text.toString().trim();
        return value.length() <= 260 ? value : value.substring(0, 257).trim() + "...";
    }

    private String storyText(List<Map<String, Object>> transcript) {
        StringBuilder text = new StringBuilder();
        for (Map<String, Object> node : sortedByStart(transcript)) {
            String line = captionText(node);
            if (!line.isBlank()) {
                if (!text.isEmpty()) {
                    text.append(' ');
                }
                text.append(line);
            }
        }
        return text.toString().trim();
    }

    private String truncateTitle(String value) {
        String text = defaultString(value, "Story").trim();
        if (text.length() <= 84) {
            return text;
        }
        return text.substring(0, 81).trim() + "...";
    }

    private String captionText(Map<String, Object> node) {
        return defaultString(firstNonBlank(node.get("transcript"), node.get("text"), node.get("caption"), node.get("summary")), "").trim();
    }

    private String bestCaptionForRange(List<Map<String, Object>> transcript, double start, double end) {
        StringBuilder text = new StringBuilder();
        for (Map<String, Object> node : transcriptForRange(transcript, start, end)) {
            String line = captionText(node);
            if (!line.isBlank()) {
                if (!text.isEmpty()) {
                    text.append(' ');
                }
                text.append(line);
            }
            if (text.length() > 120) {
                break;
            }
        }
        String value = text.toString().trim();
        return value.length() <= 140 ? value : value.substring(0, 137).trim() + "...";
    }

    private Map<String, Object> nearestTimedItem(List<Map<String, Object>> items, double timestamp) {
        Map<String, Object> best = new LinkedHashMap<>();
        double bestDistance = Double.MAX_VALUE;
        for (Map<String, Object> item : listValue(items)) {
            double itemTime = doubleValue(item.get("timestampSeconds"), doubleValue(item.get("start"), 0.0));
            double distance = Math.abs(itemTime - timestamp);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = item;
            }
        }
        return best;
    }

    private Map<String, Object> itemForTimestamp(List<Map<String, Object>> items, double timestamp) {
        for (Map<String, Object> item : listValue(items)) {
            double start = doubleValue(item.get("start"), doubleValue(item.get("timestampSeconds"), 0.0));
            double end = doubleValue(item.get("end"), start + 0.5);
            if (timestamp >= start && timestamp < end) {
                return item;
            }
        }
        return items == null || items.isEmpty() ? new LinkedHashMap<>() : items.get(0);
    }

    private String shotPurpose(int shotIndex, double start, double storyStart, double storyEnd) {
        if (shotIndex == 1 || start - storyStart < 3.0) {
            return "HOOK";
        }
        if (storyEnd - start < 5.0) {
            return "CONCLUSION";
        }
        if (shotIndex % 5 == 0) {
            return "EVIDENCE";
        }
        if (shotIndex % 3 == 0) {
            return "REVEAL";
        }
        return "CLAIM";
    }

    private String layoutForShot(int shotIndex, String purpose) {
        if ("EVIDENCE".equals(purpose)) {
            return "EVIDENCE_VIEW";
        }
        if ("CONCLUSION".equals(purpose)) {
            return "CONCLUSION_CARD";
        }
        return switch (shotIndex % 4) {
            case 0 -> "DOUBLE_SCREEN_VERTICAL";
            case 1 -> "FULL_SCREEN_SPEAKER";
            case 2 -> "FULL_FOOTAGE";
            default -> "PICTURE_IN_PICTURE";
        };
    }

    private String retentionEffectForShot(int shotIndex, String purpose) {
        if ("HOOK".equals(purpose)) {
            return "PUNCH_ZOOM";
        }
        if ("EVIDENCE".equals(purpose)) {
            return "HIGHLIGHT_PULSE";
        }
        if ("REVEAL".equals(purpose)) {
            return "QUOTE_POP";
        }
        return shotIndex % 2 == 0 ? "SWIPE_TRANSITION" : "NONE";
    }

    private String attentionCueForSecond(int second, String text) {
        if (second < 3) {
            return "hook";
        }
        if (attentionScore(text) > 0.7) {
            return "claim_or_reveal";
        }
        if (second > 0 && second % 8 == 0) {
            return "pattern_interrupt";
        }
        return "maintain_context";
    }

    private double attentionScore(String text) {
        String value = defaultString(text, "").toLowerCase(Locale.ROOT);
        double score = 0.35;
        if (value.contains("?")) score += 0.12;
        if (value.matches(".*\\d.*")) score += 0.10;
        String[] strongWords = {"secret", "truth", "why", "how", "but", "never", "changed", "decision", "shock", "money", "proof", "reveal", "mistake", "warning", "risk", "crash"};
        for (String word : strongWords) {
            if (value.contains(word)) {
                score += 0.035;
            }
        }
        int words = value.isBlank() ? 0 : value.split("\\s+").length;
        if (words >= 8 && words <= 28) score += 0.08;
        if (words > 42) score -= 0.08;
        return Math.max(0.15, Math.min(0.95, score));
    }

    private String dropRiskLabel(double score, int shotCount, double duration) {
        if (score < 0.45 || (duration > 20.0 && shotCount < 4)) {
            return "HIGH";
        }
        if (score < 0.62 || (duration > 30.0 && shotCount < 6)) {
            return "MEDIUM";
        }
        return "LOW";
    }

    private List<String> recommendedEffectsForAttention(double score) {
        if (score < 0.5) {
            return List.of("PUNCH_ZOOM", "QUOTE_POP", "SWIPE_TRANSITION");
        }
        if (score < 0.7) {
            return List.of("PUNCH_ZOOM", "HIGHLIGHT_PULSE");
        }
        return List.of("QUOTE_POP", "HIGHLIGHT_PULSE");
    }

    private CreatorAsset sourceAssetForPart(CreatorShortVideo video, Map<String, Object> part) {
        UUID assetId = uuidValue(part.get("sourceAssetId"));
        if (assetId == null) {
            throw new IllegalStateException("Uploaded part is missing a source asset id.");
        }
        return assetRepository.findById(assetId)
                .orElseThrow(() -> new IllegalStateException("Uploaded part source asset was not found: " + assetId));
    }

    private List<Map<String, Object>> transcriptNodesForAnalysis(CreatorShortVideo video, Map<String, Object> ingestion) {
        Map<String, Object> transcriptTimeline = mapValue(ingestion.get("transcriptTimeline"));
        List<Map<String, Object>> nodes = listValue(transcriptTimeline.get("nodes"));
        if (!nodes.isEmpty()) {
            return nodes;
        }
        Map<String, Object> payload = mapValue(video == null ? null : video.getTranscriptPayload());
        return listValue(payload.get("nodes"));
    }

    private List<Map<String, Object>> localTranscriptForPart(List<Map<String, Object>> transcript, double partStart, double partEnd, int partNumber) {
        List<Map<String, Object>> local = new ArrayList<>();
        for (Map<String, Object> node : listValue(transcript)) {
            double start = doubleValue(node.get("start"), 0.0);
            double end = Math.max(start, doubleValue(node.get("end"), start + 0.2));
            if (end < partStart || start > partEnd) {
                continue;
            }
            Map<String, Object> copy = new LinkedHashMap<>(node);
            copy.put("partNumber", partNumber);
            copy.put("globalStart", round3(start));
            copy.put("globalEnd", round3(end));
            copy.put("start", round3(Math.max(0.0, start - partStart)));
            copy.put("end", round3(Math.max(0.2, end - partStart)));
            local.add(copy);
        }
        return local;
    }

    private List<Map<String, Object>> offsetTranscriptNodes(List<Map<String, Object>> nodes, double offsetSeconds, int partNumber, int firstIndex) {
        List<Map<String, Object>> shifted = new ArrayList<>();
        int index = Math.max(1, firstIndex);
        for (Map<String, Object> node : listValue(nodes)) {
            String text = defaultString(firstNonBlank(node.get("transcript"), node.get("text"), node.get("summary")), "").trim();
            if (text.isBlank()) {
                continue;
            }
            double start = Math.max(0.0, doubleValue(node.get("start"), 0.0)) + offsetSeconds;
            double end = Math.max(start + 0.2, doubleValue(node.get("end"), doubleValue(node.get("start"), 0.0) + 1.0) + offsetSeconds);
            Map<String, Object> copy = new LinkedHashMap<>(node);
            copy.put("id", "p%03d-n%04d".formatted(partNumber, index));
            copy.put("partNumber", partNumber);
            copy.put("start", round3(start));
            copy.put("end", round3(end));
            copy.put("transcript", text);
            copy.put("text", text);
            shifted.add(copy);
            index++;
        }
        return shifted;
    }

    private List<Map<String, Object>> offsetSceneMaps(List<Map<String, Object>> scenes, double offsetSeconds, int partNumber) {
        List<Map<String, Object>> shifted = new ArrayList<>();
        int index = 1;
        for (Map<String, Object> scene : listValue(scenes)) {
            String localId = defaultString(scene.get("id"), "scene-%03d".formatted(index));
            Map<String, Object> copy = new LinkedHashMap<>(scene);
            String sceneId = "p%03d-%s".formatted(partNumber, localId);
            copy.put("id", sceneId);
            copy.put("sceneId", sceneId);
            copy.put("partNumber", partNumber);
            copy.put("localStart", scene.get("start"));
            copy.put("localEnd", scene.get("end"));
            copy.put("start", round3(doubleValue(scene.get("start"), 0.0) + offsetSeconds));
            copy.put("end", round3(doubleValue(scene.get("end"), doubleValue(scene.get("start"), 0.0) + 0.2) + offsetSeconds));
            if (scene.get("representativeTimestamp") != null) {
                copy.put("representativeTimestamp", round3(doubleValue(scene.get("representativeTimestamp"), 0.0) + offsetSeconds));
            }
            shifted.add(copy);
            index++;
        }
        return shifted;
    }

    private List<Map<String, Object>> offsetFrameMaps(List<Map<String, Object>> frames, double offsetSeconds, int partNumber) {
        List<Map<String, Object>> shifted = new ArrayList<>();
        int index = 1;
        for (Map<String, Object> frame : listValue(frames)) {
            String localId = defaultString(frame.get("id"), "F%03d".formatted(index));
            Map<String, Object> copy = new LinkedHashMap<>(frame);
            copy.remove("thumbnailDataUrl");
            copy.put("id", "p%03d-%s".formatted(partNumber, localId));
            copy.put("partNumber", partNumber);
            copy.put("localTimestampSeconds", frame.get("timestampSeconds"));
            copy.put("timestampSeconds", round3(doubleValue(frame.get("timestampSeconds"), 0.0) + offsetSeconds));
            if (frame.get("sceneId") != null) {
                copy.put("sceneId", "p%03d-%s".formatted(partNumber, frame.get("sceneId")));
            }
            shifted.add(copy);
            index++;
        }
        return shifted;
    }

    private Map<String, Object> transcriptTimelineGraph(List<Map<String, Object>> transcript, String source) {
        List<Map<String, Object>> nodes = new ArrayList<>();
        List<Map<String, Object>> edges = new ArrayList<>();
        String previousId = "";
        double timelineEnd = 0.0;
        for (Map<String, Object> sourceNode : listValue(transcript)) {
            String text = defaultString(firstNonBlank(sourceNode.get("transcript"), sourceNode.get("text")), "").trim();
            if (text.isBlank()) {
                continue;
            }
            double start = Math.max(0.0, doubleValue(sourceNode.get("start"), 0.0));
            double end = Math.max(start + 0.2, doubleValue(sourceNode.get("end"), start + 1.0));
            String id = defaultString(sourceNode.get("id"), "n-%04d".formatted(nodes.size() + 1));
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", id);
            node.put("label", defaultString(sourceNode.get("speaker"), "Dialogue") + " " + (nodes.size() + 1));
            node.put("type", "DIALOGUE");
            node.put("ordinal", nodes.size() + 1);
            node.put("start", round3(start));
            node.put("end", round3(end));
            node.put("durationSeconds", round3(end - start));
            node.put("speaker", defaultString(sourceNode.get("speaker"), "Speaker"));
            node.put("transcript", text);
            node.put("partNumber", sourceNode.get("partNumber"));
            nodes.add(node);
            if (!previousId.isBlank()) {
                Map<String, Object> edge = new LinkedHashMap<>();
                edge.put("from", previousId);
                edge.put("to", id);
                edge.put("type", "NEXT_DIALOGUE");
                edge.put("reason", "Adjacent transcript dialogue in uploaded timeline.");
                edges.add(edge);
            }
            previousId = id;
            timelineEnd = Math.max(timelineEnd, end);
        }
        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("graphViews", List.of("Transcript Timeline Graph", "Conversation Graph", "Scene Graph"));
        graph.put("analysisSource", defaultString(source, "timeline_ingestion_transcript"));
        graph.put("source", defaultString(source, "timeline_ingestion_transcript"));
        graph.put("nodes", nodes);
        graph.put("edges", edges);
        graph.put("confidence", nodes.isEmpty() ? 0.0 : 0.9);
        graph.put("timelineStart", 0);
        graph.put("timelineEnd", round3(timelineEnd));
        graph.put("metadata", Map.of(
                "source", defaultString(source, "timeline_ingestion_transcript"),
                "nodeType", "DIALOGUE",
                "nodeCount", nodes.size(),
                "edgeCount", edges.size(),
                "graphReadyDuringProcessing", true
        ));
        return graph;
    }

    private void markStageFailed(UUID videoId, String tenantId, String userId, String stageKey, String videoStatus, String message) {
        transactionTemplate.executeWithoutResult(status -> {
            CreatorShortVideo video = findVideo(videoId, tenantId, userId);
            Map<String, Object> ingestion = ingestionState(video);
            putStageState(ingestion, stageKey, "FAILED", defaultString(message, "Stage failed."), Map.of("error", defaultString(message, "Stage failed.")));
            addTrace(ingestion, stageKey.toUpperCase(Locale.ROOT) + "_FAILED", defaultString(message, "Stage failed."), Map.of("error", defaultString(message, "Stage failed.")));
            persistIngestion(video, ingestion, videoStatus);
            videoRepository.saveAndFlush(video);
        });
    }

    private TimelineIngestionResponse toResponse(CreatorShortVideo video) {
        Map<String, Object> ingestion = ingestionState(video);
        Map<String, Object> project = mapValue(ingestion.get("timelineProject"));
        List<Map<String, Object>> parts = sourceParts(ingestion);
        List<Map<String, Object>> trace = listValue(ingestion.get("trace"));
        return new TimelineIngestionResponse(
                video.getId(),
                uuidValue(ingestion.get("uploadId")),
                defaultString(video.getStatus(), stringValue(ingestion.get("status"))),
                video.getTitle(),
                intValue(ingestion.get("expectedParts"), 0),
                intValue(ingestion.get("receivedParts"), parts.size()),
                intValue(ingestion.get("processedParts"), 0),
                masterVideo(parts),
                project.isEmpty() ? timelineProject(video.getId(), defaultString(video.getTitle(), "Timeline ingestion"), ingestion) : project,
                parts,
                trace,
                mapValue(video.getMetadata()),
                video.getCreatedAt(),
                video.getUpdatedAt()
        );
    }

    private CreatorShortVideo findVideo(UUID videoId, String tenantId, String userId) {
        if (videoId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Timeline ingestion video id is required.");
        }
        return videoRepository.findByIdAndTenantIdAndUserId(videoId, tenantId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Timeline ingestion session was not found."));
    }

    private Map<String, Object> ingestionState(CreatorShortVideo video) {
        Map<String, Object> metadata = mapValue(video == null ? null : video.getMetadata());
        Map<String, Object> ingestion = mapValue(metadata.get("timelineIngestion"));
        if (ingestion.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This video was not created by timeline ingestion.");
        }
        if (!(ingestion.get("sourceParts") instanceof List<?>)) {
            ingestion.put("sourceParts", new ArrayList<Map<String, Object>>());
        }
        if (!(ingestion.get("trace") instanceof List<?>)) {
            ingestion.put("trace", new ArrayList<Map<String, Object>>());
        }
        if (!(ingestion.get("stageStates") instanceof Map<?, ?>)) {
            ingestion.put("stageStates", initialStageStates());
        }
        return ingestion;
    }

    private Map<String, Object> initialStageStates() {
        Map<String, Object> stages = new LinkedHashMap<>();
        stages.put("upload", stageState("PENDING", "Upload source video parts.", Map.of()));
        stages.put("fabricTimeline", stageState("PENDING", "Render browser-editable scene and thumbnail timeline.", Map.of()));
        stages.put("transcriptTimeline", stageState("PENDING", "Generate transcript timeline after Fabric timeline is available.", Map.of()));
        stages.put("videoAnalysis", stageState("PENDING", "Analyze video after transcript timeline is available.", Map.of()));
        stages.put("storyShots", stageState("PENDING", "Generate story candidates, shot timeline, captions, and attention metadata.", Map.of()));
        return stages;
    }

    private Map<String, Object> stageStates(Map<String, Object> ingestion) {
        Map<String, Object> stages = mapValue(ingestion.get("stageStates"));
        if (stages.isEmpty()) {
            stages.putAll(initialStageStates());
            ingestion.put("stageStates", stages);
        }
        return stages;
    }

    private void putStageState(Map<String, Object> ingestion, String key, String status, String message, Map<String, Object> details) {
        Map<String, Object> stages = stageStates(ingestion);
        Map<String, Object> state = stageState(status, message, details);
        state.put("updatedAt", OffsetDateTime.now().toString());
        stages.put(key, state);
        ingestion.put("stageStates", stages);
    }

    private Map<String, Object> stageState(String status, String message, Map<String, Object> details) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("status", defaultString(status, "PENDING"));
        state.put("message", defaultString(message, ""));
        state.put("metadata", details == null ? Map.of() : details);
        return state;
    }

    private void addTrace(Map<String, Object> ingestion, String stage, String summary, Map<String, Object> details) {
        List<Map<String, Object>> trace = listValue(ingestion.get("trace"));
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("stage", stage);
        row.put("status", "COMPLETED");
        row.put("summary", summary);
        row.put("timestamp", OffsetDateTime.now().toString());
        row.put("metadata", details == null ? Map.of() : details);
        trace.add(row);
        ingestion.put("trace", trace);
    }

    private List<Map<String, Object>> sourceParts(Map<String, Object> ingestion) {
        List<Map<String, Object>> parts = listValue(ingestion.get("sourceParts"));
        ingestion.put("sourceParts", parts);
        return parts;
    }

    private Map<String, Object> partByNumber(List<Map<String, Object>> parts, int partNumber) {
        for (Map<String, Object> part : parts) {
            if (intValue(part.get("partNumber"), 0) == partNumber) {
                return part;
            }
        }
        return null;
    }

    private List<Double> compactBoundaries(List<Double> values, double duration) {
        List<Double> sorted = values.stream()
                .filter(value -> value != null && value >= 0.0)
                .sorted()
                .toList();
        List<Double> compacted = new ArrayList<>();
        for (Double value : sorted) {
            if (compacted.isEmpty() || value - compacted.get(compacted.size() - 1) >= 2.0) {
                compacted.add(round3(value));
            }
        }
        if (duration > 0 && (compacted.isEmpty() || Math.abs(compacted.get(compacted.size() - 1) - duration) > 0.5)) {
            compacted.add(round3(duration));
        }
        return compacted;
    }

    private List<Double> uniformBoundaries(double duration, double stepSeconds) {
        double safeDuration = duration > 0 ? duration : DEFAULT_PART_DURATION_SECONDS;
        List<Double> boundaries = new ArrayList<>();
        boundaries.add(0.0);
        for (double time = Math.max(1.0, stepSeconds); time < safeDuration; time += Math.max(1.0, stepSeconds)) {
            boundaries.add(round3(time));
        }
        boundaries.add(round3(safeDuration));
        return boundaries;
    }

    private String sourcePartObjectKey(String tenantId, String userId, UUID videoId, int partNumber, String originalFilename) {
        return "creator/timeline-ingestions/%s/%s/%s/parts/part-%05d.%s".formatted(
                sanitizePath(tenantId),
                sanitizePath(userId),
                videoId,
                partNumber,
                defaultString(extension(originalFilename), "mp4")
        );
    }

    private String thumbnailObjectKey(String tenantId, String userId, UUID videoId, int partNumber, String thumbnailId) {
        return "creator/timeline-ingestions/%s/%s/%s/thumbnails/part-%05d/%s.jpg".formatted(
                sanitizePath(tenantId),
                sanitizePath(userId),
                videoId,
                partNumber,
                sanitizePath(thumbnailId)
        );
    }

    private String extensionFor(String contentType, String originalFilename) {
        String normalized = stringValue(contentType).toLowerCase(Locale.ROOT);
        if (normalized.contains("quicktime")) return ".mov";
        if (normalized.contains("webm")) return ".webm";
        if (normalized.contains("x-matroska")) return ".mkv";
        if (normalized.contains("avi")) return ".avi";
        String extension = extension(originalFilename);
        return "." + defaultString(extension, "mp4");
    }

    private String extension(String filename) {
        String value = stringValue(filename);
        int dot = value.lastIndexOf('.');
        if (dot >= 0 && dot < value.length() - 1) {
            return sanitizePath(value.substring(dot + 1)).toLowerCase(Locale.ROOT);
        }
        return "";
    }

    private String stripExtension(String filename) {
        String value = stringValue(filename);
        int dot = value.lastIndexOf('.');
        if (dot > 0) {
            return value.substring(0, dot);
        }
        return value;
    }

    private String sanitizePath(String value) {
        String sanitized = stringValue(value).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]+", "-");
        sanitized = sanitized.replaceAll("-{2,}", "-").replaceAll("^-|-$", "");
        return sanitized.isBlank() ? "unknown" : sanitized;
    }

    private void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            if (Files.isDirectory(path)) {
                try (var stream = Files.walk(path)) {
                    stream.sorted((left, right) -> right.compareTo(left)).forEach(item -> {
                        try {
                            Files.deleteIfExists(item);
                        } catch (IOException ignored) {
                        }
                    });
                }
            } else {
                Files.deleteIfExists(path);
            }
        } catch (IOException ignored) {
        }
    }

    private Map<String, Object> mapValue(Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, item) -> result.put(String.valueOf(key), item));
        }
        return result;
    }

    private List<Map<String, Object>> listValue(Object value) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Map<String, Object> copy = new LinkedHashMap<>();
                    map.forEach((key, child) -> copy.put(String.valueOf(key), child));
                    result.add(copy);
                }
            }
        }
        return result;
    }

    private UUID uuidValue(Object value) {
        try {
            return value == null ? null : UUID.fromString(String.valueOf(value));
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private boolean booleanValue(Object value, boolean fallback) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value == null) {
            return fallback;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private int intValue(Object value, int fallback) {
        try {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value));
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    private double positiveDouble(Double value, double fallback) {
        if (value == null || !Double.isFinite(value) || value <= 0) {
            return fallback;
        }
        return value;
    }

    private double doubleValue(Object value, double fallback) {
        try {
            double parsed = value == null ? fallback : Double.parseDouble(String.valueOf(value));
            return Double.isFinite(parsed) ? parsed : fallback;
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    private double clampDouble(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private double round3(double value) {
        return Math.round(value * 1000.0d) / 1000.0d;
    }

    private String format(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private String tail(String value, int max) {
        String text = stringValue(value);
        return text.length() <= max ? text : text.substring(text.length() - max);
    }

    private String safeMessage(Exception ex) {
        String message = ex == null ? "" : ex.getMessage();
        return defaultString(message, ex == null ? "Unknown error" : ex.getClass().getSimpleName());
    }

    private String safeTenantId(String value) {
        return defaultString(value, "demo-tenant");
    }

    private String safeUserId(String value) {
        return defaultString(value, "demo-user");
    }

    private String defaultString(Object value, String fallback) {
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }

    private String defaultString(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String firstNonBlank(Object... values) {
        if (values == null) {
            return "";
        }
        for (Object value : values) {
            String text = stringValue(value);
            if (!text.isBlank()) {
                return text;
            }
        }
        return "";
    }

    private record MediaInfo(int width, int height, double durationSeconds) {
        String aspectRatio() {
            if (width <= 0 || height <= 0) {
                return "16 / 9";
            }
            return width + " / " + height;
        }
    }
}
