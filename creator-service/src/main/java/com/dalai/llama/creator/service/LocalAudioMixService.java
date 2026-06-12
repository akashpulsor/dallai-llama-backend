package com.dalai.llama.creator.service;

import com.dalai.llama.creator.domain.entity.CreatorAsset;
import com.dalai.llama.creator.repository.CreatorAssetRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class LocalAudioMixService {

    private static final Duration FFMPEG_TIMEOUT = Duration.ofMinutes(30);

    private final AssetStorageService assetStorageService;
    private final CreatorAssetRepository assetRepository;

    public LocalAudioMixService(AssetStorageService assetStorageService, CreatorAssetRepository assetRepository) {
        this.assetStorageService = assetStorageService;
        this.assetRepository = assetRepository;
    }

    public RenderedAudio render(
            String tenantId,
            String userId,
            UUID scriptId,
            int shotNumber,
            UUID takeId,
            String sourceBucket,
            String sourceObjectKey,
            String sourceContentType,
            List<Map<String, Object>> layers,
            Map<String, Object> mixSettings
    ) {
        if (isBlank(sourceBucket) || isBlank(sourceObjectKey)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Uploaded take media is missing storage location.");
        }
        Path workspace = null;
        try {
            workspace = Files.createTempDirectory("creator-audio-mix-");
            Path sourcePath = workspace.resolve("source" + extensionFor(sourceContentType, sourceObjectKey));
            assetStorageService.downloadObjectToPath(sourceBucket, sourceObjectKey, sourcePath);

            List<SnippetInput> snippets = writeSnippetInputs(workspace, tenantId, userId, layers == null ? List.of() : layers);
            Path outputPath = workspace.resolve("audio-mix-" + takeId + ".wav");
            List<String> command = ffmpegCommand(sourcePath, snippets, outputPath, mixSettings == null ? Map.of() : mixSettings);
            String log = run(command);
            byte[] bytes = Files.readAllBytes(outputPath);
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("renderer", "local_ffmpeg");
            metadata.put("model", "ffmpeg-audio-mix-v1");
            metadata.put("scriptId", scriptId == null ? null : scriptId.toString());
            metadata.put("shotNumber", shotNumber);
            metadata.put("takeId", takeId == null ? null : takeId.toString());
            metadata.put("sourceContentType", sourceContentType);
            metadata.put("snippetLayerCount", snippets.size());
            metadata.put("volumeAutomationCount", automation(mixSettings == null ? null : mixSettings.get("volumeAutomation")).size());
            metadata.put("renderedAt", OffsetDateTime.now().toString());
            metadata.put("ffmpegLogTail", tail(log, 1800));
            return new RenderedAudio(
                    "audio-mix-" + takeId + ".wav",
                    "audio/wav",
                    bytes,
                    metadata
            );
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not render timeline audio mix.", ex);
        } finally {
            deleteQuietly(workspace);
        }
    }

    private List<SnippetInput> writeSnippetInputs(Path workspace, String tenantId, String userId, List<Map<String, Object>> layers) throws IOException {
        List<SnippetInput> snippets = new ArrayList<>();
        for (int index = 0; index < layers.size(); index++) {
            Map<String, Object> layer = layers.get(index) == null ? Map.of() : layers.get(index);
            if (Boolean.TRUE.equals(layer.get("muted"))) {
                continue;
            }
            UUID assetId = uuidValue(layer.get("assetId"));
            if (assetId == null) {
                continue;
            }
            Optional<CreatorAsset> maybeAsset = assetRepository.findById(assetId);
            if (maybeAsset.isEmpty()) {
                continue;
            }
            CreatorAsset asset = maybeAsset.get();
            if (!same(asset.getTenantId(), tenantId) || !same(asset.getUserId(), userId)) {
                continue;
            }
            String contentType = stringValue(asset.getContentType(), "audio/wav");
            if (!contentType.toLowerCase(Locale.ROOT).startsWith("audio/")) {
                continue;
            }
            Path path = workspace.resolve("snippet-" + snippets.size() + extensionFor(contentType, asset.getObjectKey()));
            assetStorageService.downloadObjectToPath(asset.getBucket(), asset.getObjectKey(), path);
            snippets.add(new SnippetInput(path, layer, contentType));
        }
        return snippets;
    }

    private List<String> ffmpegCommand(Path sourcePath, List<SnippetInput> snippets, Path outputPath, Map<String, Object> mixSettings) {
        List<String> command = new ArrayList<>();
        command.add("ffmpeg");
        command.add("-hide_banner");
        command.add("-y");
        command.add("-i");
        command.add(sourcePath.toString());
        for (SnippetInput snippet : snippets) {
            command.add("-i");
            command.add(snippet.path().toString());
        }
        command.add("-filter_complex");
        command.add(filterGraph(snippets, mixSettings));
        command.add("-map");
        command.add("[mix]");
        command.add("-vn");
        command.add("-ac");
        command.add("2");
        command.add("-ar");
        command.add("48000");
        command.add("-c:a");
        command.add("pcm_s16le");
        command.add(outputPath.toString());
        return command;
    }

    private String filterGraph(List<SnippetInput> snippets, Map<String, Object> mixSettings) {
        StringBuilder graph = new StringBuilder();
        List<String> labels = new ArrayList<>();
        List<Map<String, Object>> automation = automation(mixSettings.get("volumeAutomation"));
        graph.append("[0:a]")
                .append(volumeFilters("dialogue", 0.0, automation))
                .append("aformat=sample_fmts=fltp:sample_rates=48000:channel_layouts=stereo[a0]");
        labels.add("[a0]");
        for (int index = 0; index < snippets.size(); index++) {
            SnippetInput snippet = snippets.get(index);
            Map<String, Object> layer = snippet.layer();
            String track = normalizeTrack(firstText(layer.get("layerType"), layer.get("type"), "foley"));
            double start = Math.max(0.0, number(firstNonNull(layer.get("startSeconds"), layer.get("startTime"), layer.get("timingSeconds")), 0.0));
            double end = Math.max(start + 0.1, number(firstNonNull(layer.get("endSeconds"), layer.get("endTime")), start + 1.0));
            double duration = Math.max(0.1, end - start);
            double gainDb = number(firstNonNull(layer.get("volumeDb"), layer.get("gainDb")), defaultTrackDb(track, mixSettings));
            String label = "a" + (index + 1);
            graph.append(";")
                    .append("[").append(index + 1).append(":a]")
                    .append("atrim=0:").append(format(duration)).append(",")
                    .append("asetpts=PTS-STARTPTS,")
                    .append("adelay=").append(Math.round(start * 1000)).append("|").append(Math.round(start * 1000)).append(",")
                    .append(volumeFilters(track, gainDb, automation))
                    .append("aformat=sample_fmts=fltp:sample_rates=48000:channel_layouts=stereo[").append(label).append("]");
            labels.add("[" + label + "]");
        }
        graph.append(";");
        for (String label : labels) {
            graph.append(label);
        }
        if (labels.size() == 1) {
            graph.append("alimiter=limit=0.95[mix]");
        } else {
            graph.append("amix=inputs=").append(labels.size()).append(":duration=longest:dropout_transition=0,alimiter=limit=0.95[mix]");
        }
        return graph.toString();
    }

    private String volumeFilters(String track, double baseGainDb, List<Map<String, Object>> automation) {
        StringBuilder filters = new StringBuilder();
        filters.append("volume=volume=").append(format(dbToLinear(baseGainDb))).append(",");
        for (Map<String, Object> item : automation) {
            String itemTrack = normalizeTrack(firstText(item.get("trackId"), item.get("layerType"), "dialogue"));
            if (!trackMatches(track, itemTrack)) {
                continue;
            }
            double start = Math.max(0.0, number(firstNonNull(item.get("startSeconds"), item.get("startTime")), 0.0));
            double end = Math.max(start + 0.1, number(firstNonNull(item.get("endSeconds"), item.get("endTime")), start + 1.0));
            double gainDb = number(firstNonNull(item.get("volumeDb"), item.get("gainDb")), 0.0);
            filters.append("volume=volume=")
                    .append(format(dbToLinear(gainDb)))
                    .append(":enable='between(t,")
                    .append(format(start))
                    .append(",")
                    .append(format(end))
                    .append(")',");
        }
        return filters.toString();
    }

    private String run(List<String> command) {
        try {
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(FFMPEG_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            String output = new String(process.getInputStream().readAllBytes());
            if (!finished) {
                process.destroyForcibly();
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "FFmpeg audio mix timed out.");
            }
            if (process.exitValue() != 0) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "FFmpeg audio mix failed: " + tail(output, 3000));
            }
            return output;
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "FFmpeg is not available for local audio mixing. Install ffmpeg in the creator-service runtime image.", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "FFmpeg audio mix was interrupted.", ex);
        }
    }

    private List<Map<String, Object>> automation(Object value) {
        if (!(value instanceof List<?> items)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : items) {
            if (item instanceof Map<?, ?> raw) {
                Map<String, Object> normalized = new LinkedHashMap<>();
                raw.forEach((key, rawValue) -> normalized.put(String.valueOf(key), rawValue));
                result.add(normalized);
            }
        }
        return result;
    }

    private double defaultTrackDb(String track, Map<String, Object> mixSettings) {
        return switch (normalizeTrack(track)) {
            case "music", "background_music" -> number(mixSettings.get("musicBedDb"), -18.0);
            case "ambience", "ambient_bed" -> number(mixSettings.get("ambienceBedDb"), -22.0);
            case "foley", "sfx", "sync_hit" -> number(mixSettings.get("foleyDuckingDb"), -6.0);
            default -> 0.0;
        };
    }

    private boolean trackMatches(String source, String requested) {
        String left = normalizeTrack(source);
        String right = normalizeTrack(requested);
        if (left.equals(right)) {
            return true;
        }
        if (right.equals("background_music")) {
            return left.equals("music") || left.equals("background_music");
        }
        if (right.equals("ambience")) {
            return left.equals("ambience") || left.equals("ambient_bed");
        }
        return false;
    }

    private String normalizeTrack(String value) {
        String normalized = stringValue(value, "foley").toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        if (normalized.equals("voice") || normalized.equals("spoken")) {
            return "dialogue";
        }
        if (normalized.equals("bgm")) {
            return "background_music";
        }
        if (List.of("dialogue", "foley", "ambience", "ambient_bed", "music", "background_music", "sfx", "sync_hit").contains(normalized)) {
            return normalized;
        }
        return "foley";
    }

    private double dbToLinear(double db) {
        return Math.pow(10.0, db / 20.0);
    }

    private String format(double value) {
        return String.format(Locale.US, "%.4f", value);
    }

    private String extensionFor(String contentType, String objectKey) {
        String key = stringValue(objectKey, "").toLowerCase(Locale.ROOT);
        int dot = key.lastIndexOf('.');
        if (dot >= 0 && dot < key.length() - 1) {
            String ext = key.substring(dot);
            if (ext.matches("\\.[a-z0-9]{2,5}")) {
                return ext;
            }
        }
        String type = stringValue(contentType, "").toLowerCase(Locale.ROOT);
        if (type.contains("mpeg") || type.contains("mp3")) return ".mp3";
        if (type.contains("mp4")) return ".mp4";
        if (type.contains("quicktime")) return ".mov";
        if (type.contains("webm")) return ".webm";
        if (type.contains("aac")) return ".aac";
        if (type.contains("wav")) return ".wav";
        return ".bin";
    }

    private void deleteQuietly(Path workspace) {
        if (workspace == null) {
            return;
        }
        try (var paths = Files.walk(workspace)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Best-effort temp cleanup.
                }
            });
        } catch (IOException ignored) {
            // Best-effort temp cleanup.
        }
    }

    private UUID uuidValue(Object value) {
        try {
            String text = stringValue(value, "");
            return text.isBlank() ? null : UUID.fromString(text);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private Object firstNonNull(Object... values) {
        for (Object value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String firstText(Object... values) {
        for (Object value : values) {
            String text = stringValue(value, "");
            if (!text.isBlank()) {
                return text;
            }
        }
        return "";
    }

    private double number(Object value, double fallback) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            String text = stringValue(value, "");
            return text.isBlank() ? fallback : Double.parseDouble(text);
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    private String stringValue(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private boolean same(String left, String right) {
        return stringValue(left, "").equals(stringValue(right, ""));
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String tail(String value, int maxChars) {
        String text = stringValue(value, "");
        return text.length() <= maxChars ? text : text.substring(text.length() - maxChars);
    }

    public record RenderedAudio(
            String filename,
            String contentType,
            byte[] bytes,
            Map<String, Object> metadata
    ) {
    }

    private record SnippetInput(
            Path path,
            Map<String, Object> layer,
            String contentType
    ) {
    }
}
