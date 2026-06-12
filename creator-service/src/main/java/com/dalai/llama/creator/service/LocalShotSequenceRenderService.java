package com.dalai.llama.creator.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class LocalShotSequenceRenderService {

    private static final Logger log = LoggerFactory.getLogger(LocalShotSequenceRenderService.class);
    private static final Duration FFMPEG_TIMEOUT = Duration.ofMinutes(45);

    private final AssetStorageService assetStorageService;

    public LocalShotSequenceRenderService(AssetStorageService assetStorageService) {
        this.assetStorageService = assetStorageService;
    }

    public RenderedSequence render(UUID scriptId, List<SequenceClip> clips) {
        if (clips == null || clips.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Accept at least one shot before rendering the final sequence preview.");
        }
        Path workspace = null;
        try {
            workspace = Files.createTempDirectory("creator-shot-sequence-");
            List<Path> normalizedClips = new ArrayList<>();
            List<Map<String, Object>> clipMetadata = new ArrayList<>();

            Path firstInput = downloadClip(workspace, clips.get(0), 0);
            Dimensions target = targetDimensions(firstInput);
            Path firstNormalized = normalizeClip(firstInput, workspace.resolve("segment-000.mp4"), target);
            normalizedClips.add(firstNormalized);
            clipMetadata.add(clipMetadata(clips.get(0), firstNormalized, target, 0));

            for (int index = 1; index < clips.size(); index++) {
                SequenceClip clip = clips.get(index);
                Path input = downloadClip(workspace, clip, index);
                Path normalized = normalizeClip(input, workspace.resolve("segment-%03d.mp4".formatted(index)), target);
                normalizedClips.add(normalized);
                clipMetadata.add(clipMetadata(clip, normalized, target, index));
            }

            Path concatFile = workspace.resolve("concat.txt");
            Files.writeString(concatFile, concatFile(normalizedClips));
            Path outputPath = workspace.resolve("accepted-sequence-preview.mp4");
            Path logPath = workspace.resolve("ffmpeg-sequence.log");
            run(List.of(
                    "ffmpeg",
                    "-hide_banner",
                    "-y",
                    "-f",
                    "concat",
                    "-safe",
                    "0",
                    "-i",
                    concatFile.toString(),
                    "-c",
                    "copy",
                    "-movflags",
                    "+faststart",
                    outputPath.toString()
            ), logPath, "FFmpeg final sequence concat failed");

            byte[] bytes = Files.readAllBytes(outputPath);
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("renderer", "local_ffmpeg");
            metadata.put("model", "ffmpeg-accepted-shot-sequence-v1");
            metadata.put("scriptId", scriptId == null ? null : scriptId.toString());
            metadata.put("clipCount", clips.size());
            metadata.put("targetWidth", target.width());
            metadata.put("targetHeight", target.height());
            metadata.put("clips", clipMetadata);
            metadata.put("renderedAt", OffsetDateTime.now().toString());
            metadata.put("ffmpegLogTail", tail(readQuietly(logPath), 1800));
            return new RenderedSequence("accepted-sequence-preview.mp4", "video/mp4", bytes, metadata);
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not render accepted shot sequence.", ex);
        } finally {
            deleteQuietly(workspace);
        }
    }

    private Path downloadClip(Path workspace, SequenceClip clip, int index) {
        if (isBlank(clip.bucket()) || isBlank(clip.objectKey())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Accepted shot " + clip.shotNumber() + " is missing video storage.");
        }
        Path path = workspace.resolve("input-%03d%s".formatted(index, extensionFor(clip.contentType(), clip.objectKey(), ".mp4")));
        assetStorageService.downloadObjectToPath(clip.bucket(), clip.objectKey(), path);
        return path;
    }

    private Path normalizeClip(Path input, Path output, Dimensions target) {
        boolean hasAudio = hasAudio(input);
        List<String> command = new ArrayList<>();
        command.add("ffmpeg");
        command.add("-hide_banner");
        command.add("-y");
        command.add("-i");
        command.add(input.toString());
        if (!hasAudio) {
            command.add("-f");
            command.add("lavfi");
            command.add("-i");
            command.add("anullsrc=channel_layout=stereo:sample_rate=48000");
        }
        command.add("-map");
        command.add("0:v:0");
        command.add("-map");
        command.add(hasAudio ? "0:a?" : "1:a:0");
        command.add("-vf");
        command.add("scale=w=%d:h=%d:force_original_aspect_ratio=decrease,pad=%d:%d:(ow-iw)/2:(oh-ih)/2,setsar=1"
                .formatted(target.width(), target.height(), target.width(), target.height()));
        command.add("-r");
        command.add("30");
        command.add("-c:v");
        command.add("libx264");
        command.add("-preset");
        command.add("veryfast");
        command.add("-crf");
        command.add("20");
        command.add("-pix_fmt");
        command.add("yuv420p");
        command.add("-c:a");
        command.add("aac");
        command.add("-b:a");
        command.add("192k");
        command.add("-ar");
        command.add("48000");
        command.add("-ac");
        command.add("2");
        command.add("-shortest");
        command.add("-movflags");
        command.add("+faststart");
        command.add(output.toString());
        run(command, output.resolveSibling(output.getFileName() + ".log"), "FFmpeg shot sequence segment normalization failed");
        return output;
    }

    private Dimensions targetDimensions(Path input) {
        Dimensions source = probeDimensions(input);
        boolean horizontal = source.width() >= source.height();
        return horizontal ? new Dimensions(1280, 720) : new Dimensions(720, 1280);
    }

    private Dimensions probeDimensions(Path input) {
        try {
            Process process = new ProcessBuilder(
                    "ffprobe",
                    "-v",
                    "error",
                    "-select_streams",
                    "v:0",
                    "-show_entries",
                    "stream=width,height",
                    "-of",
                    "csv=s=x:p=0",
                    input.toString()
            ).redirectErrorStream(true).start();
            boolean finished = process.waitFor(20, TimeUnit.SECONDS);
            String output = new String(process.getInputStream().readAllBytes()).trim();
            if (finished && process.exitValue() == 0 && output.matches("\\d+x\\d+")) {
                String[] parts = output.split("x", 2);
                return new Dimensions(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
            }
        } catch (IOException | InterruptedException | NumberFormatException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("Could not probe video dimensions for accepted sequence input={}", input, ex);
        }
        return new Dimensions(720, 1280);
    }

    private boolean hasAudio(Path input) {
        try {
            Process process = new ProcessBuilder(
                    "ffprobe",
                    "-v",
                    "error",
                    "-select_streams",
                    "a:0",
                    "-show_entries",
                    "stream=codec_type",
                    "-of",
                    "csv=p=0",
                    input.toString()
            ).redirectErrorStream(true).start();
            boolean finished = process.waitFor(20, TimeUnit.SECONDS);
            String output = new String(process.getInputStream().readAllBytes()).trim();
            return finished && process.exitValue() == 0 && output.toLowerCase(Locale.ROOT).contains("audio");
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("Could not probe audio stream for accepted sequence input={}", input, ex);
            return true;
        }
    }

    private void run(List<String> command, Path logPath, String failureMessage) {
        try {
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(logPath.toFile()))
                    .start();
            boolean finished = process.waitFor(FFMPEG_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, failureMessage + ": timed out.");
            }
            if (process.exitValue() != 0) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, failureMessage + ": " + tail(readQuietly(logPath), 3000));
            }
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "FFmpeg is not available for accepted shot sequence rendering.", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Accepted shot sequence rendering was interrupted.", ex);
        }
    }

    private String concatFile(List<Path> paths) {
        return paths.stream()
                .map(path -> "file '" + path.toAbsolutePath().toString().replace("\\", "/").replace("'", "'\\''") + "'")
                .reduce((left, right) -> left + System.lineSeparator() + right)
                .orElse("");
    }

    private Map<String, Object> clipMetadata(SequenceClip clip, Path normalizedPath, Dimensions target, int index) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("index", index);
        metadata.put("takeId", clip.takeId() == null ? null : clip.takeId().toString());
        metadata.put("shotNumber", clip.shotNumber());
        metadata.put("assetId", clip.assetId() == null ? null : clip.assetId().toString());
        metadata.put("sourceRole", clip.sourceRole());
        metadata.put("targetWidth", target.width());
        metadata.put("targetHeight", target.height());
        metadata.put("normalizedFile", normalizedPath.getFileName().toString());
        return metadata;
    }

    private String extensionFor(String contentType, String objectKey, String fallback) {
        String key = stringValue(objectKey, "").toLowerCase(Locale.ROOT);
        int dot = key.lastIndexOf('.');
        if (dot >= 0 && dot < key.length() - 1) {
            return key.substring(dot);
        }
        String normalized = stringValue(contentType, "").toLowerCase(Locale.ROOT);
        if (normalized.contains("quicktime")) return ".mov";
        if (normalized.contains("webm")) return ".webm";
        return fallback;
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

    private String readQuietly(Path path) {
        try {
            return path == null || !Files.exists(path) ? "" : Files.readString(path);
        } catch (IOException ignored) {
            return "";
        }
    }

    private String tail(String value, int maxLength) {
        String text = stringValue(value, "");
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(text.length() - maxLength);
    }

    private String stringValue(Object value, String fallback) {
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public record SequenceClip(
            UUID takeId,
            int shotNumber,
            UUID assetId,
            String bucket,
            String objectKey,
            String contentType,
            String sourceRole
    ) {
    }

    public record RenderedSequence(
            String filename,
            String contentType,
            byte[] bytes,
            Map<String, Object> metadata
    ) {
    }

    private record Dimensions(int width, int height) {
    }
}
