package com.dalai.llama.creator.service;

import com.dalai.llama.creator.domain.entity.CreatorGenerationJob;
import com.dalai.llama.creator.dto.response.GenerationJobResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * New (not yet wired into the render pipeline) decomposition step for the Editor flow:
 * takes an uploaded video, samples it into thumbnail frames (via the same ffmpeg
 * extraction {@link LocalVideoFrameExtractionService} already used for polished shot
 * takes), and separately decouples its audio track into its own downloadable asset.
 * Both are handed back on the generation job so a future Editor UI can present frames
 * for scrubbing/selection and the isolated audio track for a separate edit pass.
 */
@Service
public class CreatorPostProductionVideoDecomposeService {

    private static final Logger log = LoggerFactory.getLogger(CreatorPostProductionVideoDecomposeService.class);
    private static final String JOB_TYPE = "POST_PRODUCTION_VIDEO_DECOMPOSE";
    private static final long MAX_VIDEO_BYTES = 500L * 1024L * 1024L;
    private static final int DEFAULT_FRAME_SAMPLE_COUNT = 12;
    private static final Duration COMMAND_TIMEOUT = Duration.ofMinutes(10);

    private final AssetStorageService assetStorageService;
    private final LocalVideoFrameExtractionService frameExtractionService;
    private final GenerationJobService generationJobService;
    private final TaskExecutor taskExecutor;

    public CreatorPostProductionVideoDecomposeService(
            AssetStorageService assetStorageService,
            LocalVideoFrameExtractionService frameExtractionService,
            GenerationJobService generationJobService,
            @Qualifier("creatorTaskExecutor") TaskExecutor taskExecutor
    ) {
        this.assetStorageService = assetStorageService;
        this.frameExtractionService = frameExtractionService;
        this.generationJobService = generationJobService;
        this.taskExecutor = taskExecutor;
    }

    public GenerationJobResponse startDecompose(
            MultipartFile file,
            Integer requestedFrameCount,
            String tenantId,
            String userId
    ) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a video to decompose.");
        }
        if (file.getSize() > MAX_VIDEO_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Video must be 500 MB or smaller.");
        }
        String contentType = defaultString(file.getContentType(), "video/mp4").toLowerCase(Locale.ROOT);
        String safeTenantId = defaultString(tenantId, "unknown");
        String safeUserId = defaultString(userId, "anonymous");

        String objectKey = "post-production/video-decompose/%s/source%s".formatted(
                UUID.randomUUID(), extensionFor(contentType, file.getOriginalFilename()));
        AssetStorageService.StoredObject sourceVideo;
        try {
            sourceVideo = assetStorageService.uploadCreatorAsset(objectKey, file.getBytes(), contentType, Duration.ofHours(6));
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read the uploaded video.", ex);
        }

        int frameCount = requestedFrameCount == null || requestedFrameCount <= 0
                ? DEFAULT_FRAME_SAMPLE_COUNT
                : requestedFrameCount;

        Map<String, Object> jobInput = new LinkedHashMap<>();
        jobInput.put("bucket", sourceVideo.bucket());
        jobInput.put("objectKey", sourceVideo.objectKey());
        jobInput.put("contentType", sourceVideo.contentType());
        jobInput.put("requestedFrameCount", frameCount);
        CreatorGenerationJob job = generationJobService.startGenerationJob(JOB_TYPE, safeTenantId, safeUserId, null, jobInput);

        taskExecutor.execute(() -> runDecompose(job.getId(), sourceVideo, frameCount, safeTenantId, safeUserId));

        return generationJobService.toResponse(job);
    }

    private void runDecompose(
            UUID jobId,
            AssetStorageService.StoredObject sourceVideo,
            int frameCount,
            String tenantId,
            String userId
    ) {
        try {
            LocalVideoFrameExtractionService.FrameTimeline frameTimeline = frameExtractionService.extract(
                    null, 0, null, null, null,
                    sourceVideo.bucket(), sourceVideo.objectKey(), sourceVideo.contentType(), frameCount
            );

            AssetStorageService.StoredObject audioAsset = extractAudioTrack(sourceVideo, tenantId, userId);

            Map<String, Object> output = new LinkedHashMap<>();
            output.put("frames", frameTimeline.frames());
            output.put("frameMetadata", frameTimeline.metadata());
            output.put("sourceVideo", assetSummary(sourceVideo));
            output.put("audioAsset", audioAsset == null ? Map.of() : assetSummary(audioAsset));
            output.put("decomposedAt", OffsetDateTime.now().toString());
            generationJobService.completeGenerationJob(jobId, output);
        } catch (RuntimeException ex) {
            log.error("Post-production video decompose failed jobId={} bucket={} objectKey={}",
                    jobId, sourceVideo.bucket(), sourceVideo.objectKey(), ex);
            generationJobService.failGenerationJob(jobId, defaultString(ex.getMessage(), ex.getClass().getSimpleName()));
        }
    }

    private AssetStorageService.StoredObject extractAudioTrack(
            AssetStorageService.StoredObject sourceVideo, String tenantId, String userId
    ) {
        Path workspace = null;
        try {
            workspace = Files.createTempDirectory("creator-video-decompose-");
            Path sourcePath = workspace.resolve("source" + extensionFor(sourceVideo.contentType(), sourceVideo.objectKey()));
            assetStorageService.downloadObjectToPath(sourceVideo.bucket(), sourceVideo.objectKey(), sourcePath);

            Path audioPath = workspace.resolve("audio.m4a");
            run(List.of(
                    "ffmpeg", "-hide_banner", "-y",
                    "-i", sourcePath.toString(),
                    "-vn",
                    "-acodec", "aac",
                    "-b:a", "192k",
                    audioPath.toString()
            ));
            if (!Files.exists(audioPath) || Files.size(audioPath) == 0) {
                log.warn("Source video for tenant={} user={} has no audio track to decouple.", tenantId, userId);
                return null;
            }

            String audioObjectKey = "post-production/video-decompose/%s/audio.m4a".formatted(UUID.randomUUID());
            return assetStorageService.uploadCreatorAssetFromPath(audioObjectKey, audioPath, "audio/mp4", Duration.ofHours(6));
        } catch (IOException ex) {
            log.warn("Could not decouple audio track: {}", ex.getMessage());
            return null;
        } finally {
            deleteQuietly(workspace);
        }
    }

    private Map<String, Object> assetSummary(AssetStorageService.StoredObject asset) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("bucket", asset.bucket());
        summary.put("objectKey", asset.objectKey());
        summary.put("contentType", asset.contentType());
        summary.put("sizeBytes", asset.sizeBytes());
        summary.put("assetUrl", asset.signedUrl());
        summary.put("signedUrl", asset.signedUrl());
        return summary;
    }

    private void run(List<String> command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            boolean finished = process.waitFor(COMMAND_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            String output = new String(process.getInputStream().readAllBytes());
            if (!finished) {
                process.destroyForcibly();
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "FFmpeg audio decouple timed out.");
            }
            if (process.exitValue() != 0) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "FFmpeg audio decouple failed: " + tail(output, 3000));
            }
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "FFmpeg is not available for audio decouple.", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "FFmpeg audio decouple was interrupted.", ex);
        }
    }

    private void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try (var stream = Files.walk(path)) {
            stream.sorted((left, right) -> right.compareTo(left)).forEach(item -> {
                try {
                    Files.deleteIfExists(item);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    private String tail(String value, int max) {
        String text = value == null ? "" : value;
        return text.length() <= max ? text : text.substring(text.length() - max);
    }

    private String extensionFor(String contentType, String reference) {
        String normalized = defaultString(contentType, "").toLowerCase(Locale.ROOT);
        if (normalized.contains("quicktime")) return ".mov";
        if (normalized.contains("webm")) return ".webm";
        if (normalized.contains("x-matroska")) return ".mkv";
        String key = defaultString(reference, "").toLowerCase(Locale.ROOT);
        int dot = key.lastIndexOf('.');
        if (dot >= 0 && dot < key.length() - 1) {
            return key.substring(dot);
        }
        return ".mp4";
    }

    private String defaultString(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
