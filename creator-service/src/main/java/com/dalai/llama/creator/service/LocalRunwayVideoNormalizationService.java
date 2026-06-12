package com.dalai.llama.creator.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

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

@Service
public class LocalRunwayVideoNormalizationService {

    private static final Logger log = LoggerFactory.getLogger(LocalRunwayVideoNormalizationService.class);
    private static final Duration COMMAND_TIMEOUT = Duration.ofMinutes(15);
    private static final Duration SIGNED_URL_TTL = Duration.ofDays(7);
    private static final String CONTENT_TYPE_MP4 = "video/mp4";
    private static final double TARGET_FPS = 30.0d;

    private final AssetStorageService assetStorageService;

    public LocalRunwayVideoNormalizationService(AssetStorageService assetStorageService) {
        this.assetStorageService = assetStorageService;
    }

    public NormalizedVideo normalizeForRunway(
            UUID scriptId,
            int shotNumber,
            UUID takeId,
            UUID sourceAssetId,
            String sourceBucket,
            String sourceObjectKey,
            String sourceContentType
    ) {
        if (isBlank(sourceBucket) || isBlank(sourceObjectKey)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Runway source video storage location is missing.");
        }
        log.info(
                "Runway preflight normalization starting scriptId={} shotNumber={} takeId={} sourceAssetId={} sourceBucket={} sourceObjectKey={} sourceContentType={} targetFps={}",
                scriptId,
                shotNumber,
                takeId,
                sourceAssetId,
                sourceBucket,
                sourceObjectKey,
                sourceContentType,
                TARGET_FPS
        );
        Path workspace = null;
        try {
            workspace = Files.createTempDirectory("creator-runway-video-");
            Path sourcePath = workspace.resolve("source" + extensionFor(sourceContentType, sourceObjectKey));
            Path normalizedPath = workspace.resolve("runway-30fps.mp4");
            assetStorageService.downloadObjectToPath(sourceBucket, sourceObjectKey, sourcePath);

            MediaInfo before = probe(sourcePath);
            log.info(
                    "Runway preflight source media probed takeId={} sourceAssetId={} width={} height={} durationSeconds={} frameRate={} averageFrameRate={}",
                    takeId,
                    sourceAssetId,
                    before.width(),
                    before.height(),
                    round3(before.durationSeconds()),
                    before.frameRate(),
                    before.averageFrameRate()
            );
            run(List.of(
                    "ffmpeg",
                    "-hide_banner",
                    "-y",
                    "-i", sourcePath.toString(),
                    "-map", "0:v:0",
                    "-map", "0:a?",
                    "-vf", "fps=30,setsar=1",
                    "-r", "30",
                    "-vsync", "cfr",
                    "-c:v", "libx264",
                    "-preset", "veryfast",
                    "-crf", "18",
                    "-profile:v", "high",
                    "-pix_fmt", "yuv420p",
                    "-c:a", "aac",
                    "-b:a", "128k",
                    "-ar", "48000",
                    "-movflags", "+faststart",
                    normalizedPath.toString()
            ));
            long sizeBytes = Files.size(normalizedPath);
            if (sizeBytes <= 0) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Runway-normalized video was empty.");
            }
            MediaInfo after = probe(normalizedPath);

            String targetBucket = assetStorageService.creatorAssetsBucket();
            String targetObjectKey = "shot-takes-normalized/%s/%02d/%s-runway-30fps.mp4"
                    .formatted(scriptId, shotNumber, takeId);
            assetStorageService.s3Client().putObject(
                    PutObjectRequest.builder()
                            .bucket(targetBucket)
                            .key(targetObjectKey)
                            .contentType(CONTENT_TYPE_MP4)
                            .contentLength(sizeBytes)
                            .build(),
                    RequestBody.fromFile(normalizedPath)
            );
            String signedUrl = assetStorageService.signedUrl(targetBucket, targetObjectKey, SIGNED_URL_TTL);
            log.info(
                    "Runway preflight normalization completed takeId={} sourceAssetId={} outputBucket={} outputObjectKey={} outputSizeBytes={} width={} height={} durationSeconds={} frameRate={} averageFrameRate={}",
                    takeId,
                    sourceAssetId,
                    targetBucket,
                    targetObjectKey,
                    sizeBytes,
                    after.width(),
                    after.height(),
                    round3(after.durationSeconds()),
                    after.frameRate(),
                    after.averageFrameRate()
            );

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("source", "backend_ffmpeg_runway_preflight");
            metadata.put("purpose", "runway_video_to_video_requires_30fps_input");
            metadata.put("normalizedAt", OffsetDateTime.now().toString());
            metadata.put("scriptId", scriptId == null ? null : scriptId.toString());
            metadata.put("shotNumber", shotNumber);
            metadata.put("takeId", takeId == null ? null : takeId.toString());
            metadata.put("sourceAssetId", sourceAssetId == null ? null : sourceAssetId.toString());
            metadata.put("sourceBucket", sourceBucket);
            metadata.put("sourceObjectKey", sourceObjectKey);
            metadata.put("sourceContentType", sourceContentType);
            metadata.put("sourceWidth", before.width());
            metadata.put("sourceHeight", before.height());
            metadata.put("sourceDurationSeconds", round3(before.durationSeconds()));
            metadata.put("sourceFrameRate", before.frameRate());
            metadata.put("sourceAverageFrameRate", before.averageFrameRate());
            metadata.put("targetFrameRate", TARGET_FPS);
            metadata.put("outputBucket", targetBucket);
            metadata.put("outputObjectKey", targetObjectKey);
            metadata.put("outputContentType", CONTENT_TYPE_MP4);
            metadata.put("outputSizeBytes", sizeBytes);
            metadata.put("outputWidth", after.width());
            metadata.put("outputHeight", after.height());
            metadata.put("outputDurationSeconds", round3(after.durationSeconds()));
            metadata.put("outputFrameRate", after.frameRate());
            metadata.put("outputAverageFrameRate", after.averageFrameRate());
            metadata.put("outputPixelFormat", "yuv420p");
            metadata.put("outputVideoCodec", "h264");
            metadata.put("outputAudioCodec", "aac");
            metadata.put("outputFrameRateMode", "constant");
            return new NormalizedVideo(targetBucket, targetObjectKey, CONTENT_TYPE_MP4, sizeBytes, signedUrl, metadata);
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not prepare Runway 30 fps input video.", ex);
        } finally {
            deleteQuietly(workspace);
        }
    }

    private MediaInfo probe(Path sourcePath) {
        try {
            String output = run(List.of(
                    "ffprobe",
                    "-v", "error",
                    "-select_streams", "v:0",
                    "-show_entries", "stream=width,height,r_frame_rate,avg_frame_rate",
                    "-show_entries", "format=duration",
                    "-of", "default=noprint_wrappers=1:nokey=1",
                    sourcePath.toString()
            ));
            List<String> lines = output.lines()
                    .map(String::trim)
                    .filter(line -> !line.isBlank())
                    .toList();
            int width = lines.size() > 0 ? intValue(lines.get(0), 0) : 0;
            int height = lines.size() > 1 ? intValue(lines.get(1), 0) : 0;
            String frameRate = lines.size() > 2 ? lines.get(2) : "";
            String averageFrameRate = lines.size() > 3 ? lines.get(3) : "";
            double duration = lines.size() > 4 ? doubleValue(lines.get(4), 0.0d) : 0.0d;
            return new MediaInfo(width, height, duration, frameRate, averageFrameRate);
        } catch (RuntimeException ex) {
            return new MediaInfo(0, 0, 0.0d, "", "");
        }
    }

    private String run(List<String> command) {
        try {
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(COMMAND_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            String output = new String(process.getInputStream().readAllBytes());
            if (!finished) {
                process.destroyForcibly();
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "FFmpeg Runway video normalization timed out.");
            }
            if (process.exitValue() != 0) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "FFmpeg Runway video normalization failed: " + tail(output, 3000));
            }
            return output;
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "FFmpeg/ffprobe is not available for Runway video normalization.", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "FFmpeg Runway video normalization was interrupted.", ex);
        }
    }

    private String extensionFor(String contentType, String objectKey) {
        String normalized = value(contentType).toLowerCase(Locale.ROOT);
        if (normalized.contains("quicktime")) return ".mov";
        if (normalized.contains("webm")) return ".webm";
        if (normalized.contains("x-matroska")) return ".mkv";
        String key = value(objectKey).toLowerCase(Locale.ROOT);
        int dot = key.lastIndexOf('.');
        if (dot >= 0 && dot < key.length() - 1) {
            return key.substring(dot);
        }
        return ".mp4";
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
        String text = value(value);
        return text.length() <= max ? text : text.substring(text.length() - max);
    }

    private int intValue(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    private double doubleValue(String value, double fallback) {
        try {
            return Double.parseDouble(value.trim());
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    private double round3(double value) {
        return Math.round(value * 1000.0d) / 1000.0d;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String value(String value) {
        return value == null ? "" : value;
    }

    public record NormalizedVideo(
            String bucket,
            String objectKey,
            String contentType,
            long sizeBytes,
            String signedUrl,
            Map<String, Object> metadata
    ) {
    }

    private record MediaInfo(int width, int height, double durationSeconds, String frameRate, String averageFrameRate) {
    }
}
