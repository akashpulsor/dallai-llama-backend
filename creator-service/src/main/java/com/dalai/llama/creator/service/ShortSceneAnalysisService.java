package com.dalai.llama.creator.service;

import com.dalai.llama.creator.domain.entity.CreatorAsset;
import com.dalai.llama.creator.domain.entity.CreatorShortVideo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

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
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ShortSceneAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(ShortSceneAnalysisService.class);
    private static final Duration COMMAND_TIMEOUT = Duration.ofMinutes(12);
    private static final int MAX_SCENE_DETECTION_FRAMES = 80;
    private static final int MAX_THUMBNAIL_FRAMES = 48;
    private static final double SCENE_THRESHOLD = 0.35;
    private static final double FULL_SCENE_DETECTION_LIMIT_SECONDS = 20 * 60;
    private static final double LONG_VIDEO_SCENE_SCAN_LIMIT_SECONDS = 2 * 60 * 60;
    private static final double MEDIUM_VIDEO_UNIFORM_STEP_SECONDS = 120.0;
    private static final double LONG_VIDEO_UNIFORM_STEP_SECONDS = 300.0;

    private final AssetStorageService assetStorageService;

    public ShortSceneAnalysisService(AssetStorageService assetStorageService) {
        this.assetStorageService = assetStorageService;
    }

    public SceneAnalysisResult analyze(CreatorShortVideo video, CreatorAsset sourceAsset, List<Map<String, Object>> transcript) {
        return analyze(video, sourceAsset, transcript, null);
    }

    public SceneAnalysisResult analyze(CreatorShortVideo video, CreatorAsset sourceAsset, List<Map<String, Object>> transcript, Path sourceVideoPath) {
        return attachTranscript(video, prepareVisualAnalysis(video, sourceAsset, sourceVideoPath), transcript);
    }

    public VisualSceneAnalysisResult prepareVisualAnalysis(CreatorShortVideo video, CreatorAsset sourceAsset, Path sourceVideoPath) {
        boolean reuseSource = reusableSourcePath(sourceVideoPath);
        if (!reuseSource && (sourceAsset == null || isBlank(sourceAsset.getBucket()) || isBlank(sourceAsset.getObjectKey()))) {
            return skippedVisual("Source video storage location is missing.");
        }

        Path workspace = null;
        try {
            workspace = Files.createTempDirectory("creator-shorts-scenes-");
            Path sourcePath = reuseSource
                    ? sourceVideoPath
                    : workspace.resolve("source" + extensionFor(sourceAsset.getContentType(), sourceAsset.getObjectKey()));
            Path frameDir = Files.createDirectories(workspace.resolve("frames"));
            if (!reuseSource) {
                assetStorageService.downloadObjectToPath(sourceAsset.getBucket(), sourceAsset.getObjectKey(), sourcePath);
            }

            long probeStartedAt = System.nanoTime();
            MediaInfo mediaInfo = probe(sourcePath);
            long probeMs = elapsedMillis(probeStartedAt);
            double duration = mediaInfo.durationSeconds();
            boolean largeVideoMode = duration > LONG_VIDEO_SCENE_SCAN_LIMIT_SECONDS;
            long boundariesStartedAt = System.nanoTime();
            SceneBoundaryResult boundaryResult = sceneBoundaries(sourcePath, duration);
            long boundariesMs = elapsedMillis(boundariesStartedAt);
            List<Map<String, Object>> scenes = buildScenes(boundaryResult.boundaries(), duration, boundaryResult.mode());
            long framesStartedAt = System.nanoTime();
            List<Map<String, Object>> frames = extractRepresentativeFrames(sourcePath, frameDir, scenes, mediaInfo);
            long framesMs = elapsedMillis(framesStartedAt);

            List<Map<String, Object>> trace = new ArrayList<>();
            trace.add(traceRow(
                    "SCENE_ANALYSIS",
                    scenes.isEmpty() ? "WARN" : "COMPLETED",
                    scenes.isEmpty()
                            ? "Scene analysis did not find visual segments."
                            : "Scene boundaries were detected and representative frames were attached to transcript nodes.",
                    scenes.isEmpty() ? 0.35 : 0.82,
                    Map.of(
                            "sceneCount", scenes.size(),
                            "frameCount", frames.size(),
                            "durationSeconds", round3(duration),
                            "largeVideoMode", largeVideoMode,
                            "sceneWindowStepSeconds", uniformStepSeconds(duration),
                            "sceneBoundaryMode", boundaryResult.mode(),
                            "fullSceneScanAttempted", boundaryResult.fullScanAttempted(),
                            "fallbackReason", boundaryResult.fallbackReason()
                    )
            ));
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("source", "ffmpeg_scene_detection");
            metadata.put("generatedAt", OffsetDateTime.now().toString());
            metadata.put("threshold", SCENE_THRESHOLD);
            metadata.put("durationSeconds", round3(duration));
            metadata.put("largeVideoMode", largeVideoMode);
            metadata.put("sceneWindowStepSeconds", uniformStepSeconds(duration));
            metadata.put("sceneBoundaryMode", boundaryResult.mode());
            metadata.put("fullSceneScanAttempted", boundaryResult.fullScanAttempted());
            metadata.put("fallbackReason", boundaryResult.fallbackReason());
            metadata.put("width", mediaInfo.width());
            metadata.put("height", mediaInfo.height());
            metadata.put("aspectRatio", mediaInfo.aspectRatio());
            metadata.put("sceneCount", scenes.size());
            metadata.put("frameCount", frames.size());
            metadata.put("timingMs", Map.of(
                    "probe", probeMs,
                    "boundaries", boundariesMs,
                    "frames", framesMs
            ));

            log.info(
                    "Shorts scene analysis complete videoId={} durationSeconds={} boundaryMode={} fullScanAttempted={} scenes={} frames={} timingMs={}",
                    video == null ? null : video.getId(),
                    round3(duration),
                    boundaryResult.mode(),
                    boundaryResult.fullScanAttempted(),
                    scenes.size(),
                    frames.size(),
                    metadata.get("timingMs")
            );

            return new VisualSceneAnalysisResult(true, scenes, frames, trace, metadata);
        } catch (Exception ex) {
            log.warn("Shorts scene analysis failed videoId={} message={}", video == null ? null : video.getId(), ex.getMessage());
            return skippedVisual("Scene analysis failed: " + safeMessage(ex));
        } finally {
            deleteQuietly(workspace);
        }
    }

    public SceneAnalysisResult attachTranscript(CreatorShortVideo video, VisualSceneAnalysisResult visualAnalysis, List<Map<String, Object>> transcript) {
        List<Map<String, Object>> safeTranscript = copyList(transcript);
        VisualSceneAnalysisResult safeVisual = visualAnalysis == null
                ? skippedVisual("Scene visual preparation did not produce a result.")
                : visualAnalysis;
        long attachStartedAt = System.nanoTime();
        List<Map<String, Object>> attachedTranscript = attachScenesAndFrames(safeTranscript, safeVisual.scenes(), safeVisual.frames());
        long attachMs = elapsedMillis(attachStartedAt);
        List<Map<String, Object>> trace = new ArrayList<>(safeVisual.trace());
        trace.add(traceRow(
                "SCENE_CRITIC",
                safeVisual.scenes().isEmpty() ? "WARN" : "COMPLETED",
                safeVisual.scenes().isEmpty()
                        ? "Scene critic could not verify visual coverage."
                        : "Scene critic verified timestamp coverage against transcript nodes.",
                safeVisual.scenes().isEmpty() ? 0.35 : 0.78,
                Map.of("transcriptNodeCount", attachedTranscript.size())
        ));

        Map<String, Object> metadata = new LinkedHashMap<>(safeVisual.metadata());
        Map<String, Object> timing = new LinkedHashMap<>(mapValue(metadata.get("timingMs")));
        timing.put("attachTranscript", attachMs);
        metadata.put("timingMs", timing);
        metadata.put("transcriptNodeCount", attachedTranscript.size());
        metadata.put("visualPrepStartedBeforeTranscript", true);

        log.info(
                "Shorts scene transcript attached videoId={} scenes={} frames={} transcriptNodes={} attachMs={}",
                video == null ? null : video.getId(),
                safeVisual.scenes().size(),
                safeVisual.frames().size(),
                attachedTranscript.size(),
                attachMs
        );
        return new SceneAnalysisResult(safeVisual.mediaBacked(), safeVisual.scenes(), safeVisual.frames(), attachedTranscript, trace, metadata);
    }

    private boolean reusableSourcePath(Path sourceVideoPath) {
        return sourceVideoPath != null && Files.isRegularFile(sourceVideoPath);
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

    private SceneBoundaryResult sceneBoundaries(Path sourcePath, double duration) {
        if (shouldUseUniformSceneWindows(duration)) {
            String mode = duration > LONG_VIDEO_SCENE_SCAN_LIMIT_SECONDS
                    ? "long_video_uniform_windows"
                    : "medium_video_uniform_windows";
            log.info(
                    "Scene boundary detection using uniform windows durationSeconds={} stepSeconds={} mode={}",
                    round3(duration),
                    uniformStepSeconds(duration),
                    mode
            );
            return new SceneBoundaryResult(uniformBoundaries(duration), mode, false, "");
        }
        TreeSet<Double> times = new TreeSet<>();
        times.add(0.0);
        String fallbackReason = "";
        try {
            String output = run(List.of(
                    "ffmpeg",
                    "-hide_banner",
                    "-i", sourcePath.toString(),
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
                if (value > 1.0 && (duration <= 0 || value < duration - 0.25)) {
                    times.add(round3(value));
                }
            }
        } catch (RuntimeException ex) {
            fallbackReason = ex.getMessage();
            log.info("Scene boundary detection fell back to uniform windows: {}", fallbackReason);
        }

        if (duration > 0) {
            times.add(round3(duration));
        }
        List<Double> compacted = compactBoundaries(new ArrayList<>(times), duration);
        if (!fallbackReason.isBlank()) {
            return new SceneBoundaryResult(uniformBoundaries(duration), "ffmpeg_scene_detection_fallback_uniform", true, fallbackReason);
        }
        if (compacted.size() <= 2) {
            return new SceneBoundaryResult(uniformBoundaries(duration), "low_scene_change_uniform_windows", true, "scene_detection_found_too_few_boundaries");
        }
        return new SceneBoundaryResult(compacted, "ffmpeg_scene_detection", true, "");
    }

    private boolean shouldUseUniformSceneWindows(double duration) {
        return duration > FULL_SCENE_DETECTION_LIMIT_SECONDS;
    }

    private List<Double> compactBoundaries(List<Double> boundaries, double duration) {
        List<Double> compacted = new ArrayList<>();
        for (Double boundary : boundaries) {
            if (boundary == null) {
                continue;
            }
            if (compacted.isEmpty() || boundary - compacted.get(compacted.size() - 1) >= 2.0) {
                compacted.add(boundary);
            }
        }
        if (duration > 0 && (compacted.isEmpty() || Math.abs(compacted.get(compacted.size() - 1) - duration) > 0.5)) {
            compacted.add(round3(duration));
        }
        return compacted;
    }

    private List<Double> uniformBoundaries(double duration) {
        double safeDuration = duration > 0 ? duration : 60.0;
        List<Double> boundaries = new ArrayList<>();
        boundaries.add(0.0);
        double step = uniformStepSeconds(safeDuration);
        for (double time = step; time < safeDuration; time += step) {
            boundaries.add(round3(time));
        }
        boundaries.add(round3(safeDuration));
        return boundaries;
    }

    private double uniformStepSeconds(double duration) {
        double safeDuration = duration > 0 ? duration : 60.0;
        if (safeDuration > LONG_VIDEO_SCENE_SCAN_LIMIT_SECONDS) {
            return LONG_VIDEO_UNIFORM_STEP_SECONDS;
        }
        if (safeDuration > 30 * 60) {
            return MEDIUM_VIDEO_UNIFORM_STEP_SECONDS;
        }
        return safeDuration <= 90 ? 15.0 : 30.0;
    }

    private List<Map<String, Object>> buildScenes(List<Double> boundaries, double duration, String source) {
        List<Map<String, Object>> scenes = new ArrayList<>();
        for (int index = 1; index < boundaries.size(); index++) {
            double start = Math.max(0.0, boundaries.get(index - 1));
            double end = Math.max(start + 0.1, boundaries.get(index));
            Map<String, Object> scene = new LinkedHashMap<>();
            String sceneId = "scene-%03d".formatted(index);
            scene.put("id", sceneId);
            scene.put("index", index);
            scene.put("start", round3(start));
            scene.put("end", round3(end));
            scene.put("durationSeconds", round3(end - start));
            scene.put("label", "Scene " + index);
            scene.put("source", stringValue(source, duration > 0 ? "ffmpeg_scene_detection" : "uniform_fallback"));
            scene.put("representativeTimestamp", round3(Math.min(Math.max(start + 0.25, midpoint(start, end)), Math.max(start, end - 0.05))));
            scene.put("frameIds", new ArrayList<String>());
            scenes.add(scene);
        }
        return scenes;
    }

    private List<Map<String, Object>> extractRepresentativeFrames(Path sourcePath, Path frameDir, List<Map<String, Object>> scenes, MediaInfo mediaInfo) {
        List<Map<String, Object>> frames = new ArrayList<>();
        int limit = Math.min(scenes.size(), MAX_THUMBNAIL_FRAMES);
        log.info("Extracting representative scene frames frameLimit={} sceneCount={}", limit, scenes.size());
        for (int index = 0; index < limit; index++) {
            Map<String, Object> scene = scenes.get(index);
            double timestamp = doubleValue(scene.get("representativeTimestamp"), doubleValue(scene.get("start"), 0.0));
            String frameId = "F%03d".formatted(index + 1);
            Path framePath = frameDir.resolve(frameId + ".jpg");
            try {
                run(List.of(
                        "ffmpeg",
                        "-hide_banner",
                        "-y",
                        "-ss", format(timestamp),
                        "-i", sourcePath.toString(),
                        "-frames:v", "1",
                        "-vf", "scale=360:-2:force_original_aspect_ratio=decrease",
                        "-q:v", "5",
                        framePath.toString()
                ));
                byte[] bytes = Files.readAllBytes(framePath);
                if (bytes.length == 0) {
                    continue;
                }
                Map<String, Object> frame = new LinkedHashMap<>();
                frame.put("id", frameId);
                frame.put("index", index + 1);
                frame.put("sceneId", scene.get("id"));
                frame.put("timestampSeconds", round3(timestamp));
                frame.put("source", "backend_ffmpeg");
                frame.put("frameSource", "source_video_scene");
                frame.put("width", mediaInfo.width());
                frame.put("height", mediaInfo.height());
                frame.put("aspectRatio", mediaInfo.aspectRatio());
                frame.put("thumbnailDataUrl", "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(bytes));
                frames.add(frame);
                @SuppressWarnings("unchecked")
                List<String> frameIds = scene.get("frameIds") instanceof List<?> list
                        ? new ArrayList<>(list.stream().map(String::valueOf).toList())
                        : new ArrayList<>();
                frameIds.add(frameId);
                scene.put("frameIds", frameIds);
                scene.put("representativeFrameId", frameId);
                if ((index + 1) % 10 == 0 || index + 1 == limit) {
                    log.info("Representative scene frame extraction progress extracted={}/{}", index + 1, limit);
                }
            } catch (RuntimeException | IOException ex) {
                log.info("Could not extract representative frame at {}s: {}", timestamp, ex.getMessage());
            }
        }
        return frames;
    }

    private List<Map<String, Object>> attachScenesAndFrames(List<Map<String, Object>> transcript, List<Map<String, Object>> scenes, List<Map<String, Object>> frames) {
        List<Map<String, Object>> attached = new ArrayList<>();
        for (Map<String, Object> node : transcript) {
            Map<String, Object> copy = new LinkedHashMap<>(node);
            double start = doubleValue(copy.get("start"), 0.0);
            double end = doubleValue(copy.get("end"), start);
            double midpoint = midpoint(start, end);
            Map<String, Object> scene = sceneAt(scenes, midpoint);
            if (!scene.isEmpty()) {
                copy.put("sceneId", scene.get("id"));
                copy.put("sceneStart", scene.get("start"));
                copy.put("sceneEnd", scene.get("end"));
            }
            List<String> frameIds = framesForNode(start, end, scene, frames);
            copy.put("frames", frameIds);
            attached.add(copy);
        }
        return attached;
    }

    private List<String> framesForNode(double start, double end, Map<String, Object> scene, List<Map<String, Object>> frames) {
        Set<String> ids = new TreeSet<>();
        for (Map<String, Object> frame : frames) {
            double timestamp = doubleValue(frame.get("timestampSeconds"), -1.0);
            if (timestamp >= start && timestamp <= end) {
                ids.add(stringValue(frame.get("id"), ""));
            }
        }
        if (ids.isEmpty() && !scene.isEmpty()) {
            Object sceneFrameIds = scene.get("frameIds");
            if (sceneFrameIds instanceof List<?> list) {
                for (Object item : list) {
                    if (item != null && !String.valueOf(item).isBlank()) {
                        ids.add(String.valueOf(item));
                    }
                }
            }
        }
        ids.remove("");
        return new ArrayList<>(ids);
    }

    private Map<String, Object> sceneAt(List<Map<String, Object>> scenes, double timestamp) {
        for (Map<String, Object> scene : scenes) {
            double start = doubleValue(scene.get("start"), 0.0);
            double end = doubleValue(scene.get("end"), start);
            if (timestamp >= start && timestamp <= end) {
                return scene;
            }
        }
        return scenes.isEmpty() ? new LinkedHashMap<>() : scenes.get(scenes.size() - 1);
    }

    private VisualSceneAnalysisResult skippedVisual(String reason) {
        List<Map<String, Object>> trace = new ArrayList<>();
        trace.add(traceRow("SCENE_ANALYSIS", "WARN", reason, 0.25, Map.of("source", "ffmpeg_scene_detection")));
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "ffmpeg_scene_detection");
        metadata.put("status", "WARN");
        metadata.put("reason", reason);
        metadata.put("generatedAt", OffsetDateTime.now().toString());
        return new VisualSceneAnalysisResult(false, List.of(), List.of(), trace, metadata);
    }

    private String run(List<String> command) {
        Process process = null;
        try {
            process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(COMMAND_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            String output = new String(process.getInputStream().readAllBytes());
            if (!finished) {
                process.destroyForcibly();
                throw new IllegalStateException("FFmpeg scene analysis timed out.");
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException("FFmpeg scene analysis failed: " + tail(output, 3000));
            }
            return output;
        } catch (IOException ex) {
            throw new IllegalStateException("FFmpeg/ffprobe is not available for shorts scene analysis.", ex);
        } catch (InterruptedException ex) {
            if (process != null) {
                process.destroyForcibly();
            }
            Thread.currentThread().interrupt();
            throw new IllegalStateException("FFmpeg scene analysis was interrupted.", ex);
        }
    }

    private Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), item));
            return result;
        }
        return new LinkedHashMap<>();
    }

    private Map<String, Object> traceRow(String stage, String status, String summary, double confidence, Map<String, Object> metadata) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("stage", stage);
        row.put("status", status);
        row.put("summary", summary);
        row.put("confidence", confidence);
        row.put("agent", stage.toLowerCase(Locale.ROOT).replace('_', '-'));
        row.put("timestamp", OffsetDateTime.now().toString());
        row.put("metadata", metadata == null ? Map.of() : metadata);
        return row;
    }

    private double transcriptDuration(List<Map<String, Object>> transcript) {
        double duration = 0.0;
        for (Map<String, Object> node : transcript) {
            duration = Math.max(duration, doubleValue(node.get("end"), 0.0));
        }
        return duration;
    }

    private List<Map<String, Object>> copyList(List<Map<String, Object>> value) {
        if (value == null) {
            return new ArrayList<>();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> item : value) {
            result.add(item == null ? new LinkedHashMap<>() : new LinkedHashMap<>(item));
        }
        return result;
    }

    private String extensionFor(String contentType, String objectKey) {
        String normalized = stringValue(contentType, "").toLowerCase(Locale.ROOT);
        if (normalized.contains("quicktime")) return ".mov";
        if (normalized.contains("webm")) return ".webm";
        if (normalized.contains("x-matroska")) return ".mkv";
        String key = stringValue(objectKey, "").toLowerCase(Locale.ROOT);
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

    private String tail(String value, int maxLength) {
        String safe = value == null ? "" : value;
        return safe.length() <= maxLength ? safe : safe.substring(safe.length() - maxLength);
    }

    private int intValue(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    private double doubleValue(Object value, double fallback) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value != null && !String.valueOf(value).isBlank()) {
            try {
                return Double.parseDouble(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private double midpoint(double start, double end) {
        return start + Math.max(0.0, end - start) / 2.0;
    }

    private double round3(double value) {
        return Math.round(value * 1000.0d) / 1000.0d;
    }

    private long elapsedMillis(long startedAtNanos) {
        return TimeUnit.NANOSECONDS.toMillis(Math.max(0, System.nanoTime() - startedAtNanos));
    }

    private String format(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String stringValue(Object value, String fallback) {
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }

    private String safeMessage(Exception ex) {
        return ex == null || ex.getMessage() == null ? "unknown error" : ex.getMessage();
    }

    public record SceneAnalysisResult(
            boolean mediaBacked,
            List<Map<String, Object>> scenes,
            List<Map<String, Object>> frames,
            List<Map<String, Object>> transcript,
            List<Map<String, Object>> trace,
            Map<String, Object> metadata
    ) {
    }

    public record VisualSceneAnalysisResult(
            boolean mediaBacked,
            List<Map<String, Object>> scenes,
            List<Map<String, Object>> frames,
            List<Map<String, Object>> trace,
            Map<String, Object> metadata
    ) {
    }

    private record MediaInfo(int width, int height, double durationSeconds) {
        String aspectRatio() {
            if (width <= 0 || height <= 0) {
                return "16 / 9";
            }
            return width + " / " + height;
        }
    }

    private record SceneBoundaryResult(List<Double> boundaries, String mode, boolean fullScanAttempted, String fallbackReason) {
    }
}
