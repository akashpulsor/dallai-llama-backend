package com.dalai.llama.creator.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class LocalVideoFrameExtractionService {

    private static final Duration COMMAND_TIMEOUT = Duration.ofMinutes(10);

    private final AssetStorageService assetStorageService;

    public LocalVideoFrameExtractionService(AssetStorageService assetStorageService) {
        this.assetStorageService = assetStorageService;
    }

    public FrameTimeline extract(
            UUID scriptId,
            int shotNumber,
            UUID takeId,
            UUID variantId,
            UUID videoAssetId,
            String bucket,
            String objectKey,
            String contentType,
            int requestedSampleCount
    ) {
        if (isBlank(bucket) || isBlank(objectKey)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Polished video storage location is missing.");
        }
        int sampleCount = Math.max(3, Math.min(24, requestedSampleCount <= 0 ? 10 : requestedSampleCount));
        Path workspace = null;
        try {
            workspace = Files.createTempDirectory("creator-polished-frames-");
            Path sourcePath = workspace.resolve("polished-video" + extensionFor(contentType, objectKey));
            assetStorageService.downloadObjectToPath(bucket, objectKey, sourcePath);

            MediaInfo mediaInfo = probe(sourcePath);
            double duration = mediaInfo.durationSeconds() > 0 ? mediaInfo.durationSeconds() : sampleCount;
            List<Map<String, Object>> frames = new ArrayList<>();
            for (int index = 0; index < sampleCount; index++) {
                double timestamp = timestampFor(index, sampleCount, duration);
                Path framePath = workspace.resolve("frame-%02d.jpg".formatted(index + 1));
                run(List.of(
                        "ffmpeg",
                        "-hide_banner",
                        "-y",
                        "-ss", format(timestamp),
                        "-i", sourcePath.toString(),
                        "-frames:v", "1",
                        "-vf", "scale=360:-2:force_original_aspect_ratio=decrease",
                        "-q:v", "4",
                        framePath.toString()
                ));
                byte[] bytes = Files.readAllBytes(framePath);
                if (bytes.length == 0) {
                    continue;
                }
                Map<String, Object> frame = new LinkedHashMap<>();
                frame.put("id", "polished-%02d-%d".formatted(shotNumber, index + 1));
                frame.put("index", index + 1);
                frame.put("timestampSeconds", round3(timestamp));
                frame.put("thumbnailDataUrl", "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(bytes));
                frame.put("source", "backend_ffmpeg");
                frame.put("frameSource", "polished_video");
                frame.put("shotNumber", shotNumber);
                frame.put("takeId", takeId == null ? null : takeId.toString());
                frame.put("variantId", variantId == null ? null : variantId.toString());
                frame.put("videoAssetId", videoAssetId == null ? null : videoAssetId.toString());
                frame.put("width", mediaInfo.width());
                frame.put("height", mediaInfo.height());
                frame.put("aspectRatio", mediaInfo.aspectRatio());
                frames.add(frame);
            }

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("source", "backend_ffmpeg");
            metadata.put("generatedAt", OffsetDateTime.now().toString());
            metadata.put("scriptId", scriptId == null ? null : scriptId.toString());
            metadata.put("shotNumber", shotNumber);
            metadata.put("takeId", takeId == null ? null : takeId.toString());
            metadata.put("variantId", variantId == null ? null : variantId.toString());
            metadata.put("videoAssetId", videoAssetId == null ? null : videoAssetId.toString());
            metadata.put("durationSeconds", round3(duration));
            metadata.put("width", mediaInfo.width());
            metadata.put("height", mediaInfo.height());
            metadata.put("aspectRatio", mediaInfo.aspectRatio());
            metadata.put("frameCount", frames.size());
            return new FrameTimeline(frames, metadata);
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not extract polished video frames.", ex);
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
                    "-show_entries", "stream=width,height",
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
            double duration = lines.size() > 2 ? doubleValue(lines.get(2), 0.0) : 0.0;
            return new MediaInfo(width, height, duration);
        } catch (RuntimeException ex) {
            return new MediaInfo(0, 0, 0.0);
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
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "FFmpeg frame extraction timed out.");
            }
            if (process.exitValue() != 0) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "FFmpeg frame extraction failed: " + tail(output, 3000));
            }
            return output;
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "FFmpeg/ffprobe is not available for polished frame extraction.", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "FFmpeg frame extraction was interrupted.", ex);
        }
    }

    private double timestampFor(int index, int sampleCount, double duration) {
        if (duration <= 0) {
            return index;
        }
        double step = duration / Math.max(1, sampleCount);
        return Math.max(0, Math.min(Math.max(0, duration - 0.05), (index + 0.5) * step));
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

    private String format(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String value(String value) {
        return value == null ? "" : value;
    }

    public record FrameTimeline(List<Map<String, Object>> frames, Map<String, Object> metadata) {
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
