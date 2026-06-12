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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class LocalFinalVideoRenderService {

    private static final Duration FFMPEG_TIMEOUT = Duration.ofMinutes(45);

    private final AssetStorageService assetStorageService;

    public LocalFinalVideoRenderService(AssetStorageService assetStorageService) {
        this.assetStorageService = assetStorageService;
    }

    public RenderedVideo render(
            UUID scriptId,
            int shotNumber,
            UUID takeId,
            UUID variantId,
            String videoBucket,
            String videoObjectKey,
            String videoContentType,
            String audioBucket,
            String audioObjectKey,
            String audioContentType,
            List<Map<String, Object>> textOverlays,
            Map<String, Object> renderOptions
    ) {
        if (isBlank(videoBucket) || isBlank(videoObjectKey)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Polished video storage location is missing.");
        }
        Path workspace = null;
        try {
            workspace = Files.createTempDirectory("creator-final-render-");
            Path videoPath = workspace.resolve("polished-video" + extensionFor(videoContentType, videoObjectKey, ".mp4"));
            assetStorageService.downloadObjectToPath(videoBucket, videoObjectKey, videoPath);
            Path audioPath = null;
            boolean hasExternalAudio = !isBlank(audioBucket) && !isBlank(audioObjectKey);
            if (hasExternalAudio) {
                audioPath = workspace.resolve("final-audio" + extensionFor(audioContentType, audioObjectKey, ".wav"));
                assetStorageService.downloadObjectToPath(audioBucket, audioObjectKey, audioPath);
            }

            Path outputPath = workspace.resolve("final-render-" + takeId + ".mp4");
            List<Map<String, Object>> overlays = textOverlays == null ? List.of() : textOverlays;
            String log = run(ffmpegCommand(videoPath, audioPath, outputPath, overlays));
            byte[] bytes = Files.readAllBytes(outputPath);
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("renderer", "local_ffmpeg");
            metadata.put("model", "ffmpeg-final-video-render-v1");
            metadata.put("scriptId", scriptId == null ? null : scriptId.toString());
            metadata.put("shotNumber", shotNumber);
            metadata.put("takeId", takeId == null ? null : takeId.toString());
            metadata.put("variantId", variantId == null ? null : variantId.toString());
            metadata.put("externalAudioMixed", hasExternalAudio);
            metadata.put("textOverlayCount", overlays.size());
            metadata.put("renderOptions", renderOptions == null ? Map.of() : renderOptions);
            metadata.put("renderedAt", OffsetDateTime.now().toString());
            metadata.put("ffmpegLogTail", tail(log, 1800));
            return new RenderedVideo(
                    "final-render-" + takeId + ".mp4",
                    "video/mp4",
                    bytes,
                    metadata
            );
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not render final video.", ex);
        } finally {
            deleteQuietly(workspace);
        }
    }

    private List<String> ffmpegCommand(Path videoPath, Path audioPath, Path outputPath, List<Map<String, Object>> textOverlays) {
        List<String> command = new ArrayList<>();
        command.add("ffmpeg");
        command.add("-hide_banner");
        command.add("-y");
        command.add("-i");
        command.add(videoPath.toString());
        if (audioPath != null) {
            command.add("-i");
            command.add(audioPath.toString());
        }
        String overlayFilter = drawTextFilter(textOverlays);
        if (!overlayFilter.isBlank()) {
            command.add("-vf");
            command.add(overlayFilter);
        }
        command.add("-map");
        command.add("0:v:0");
        command.add("-map");
        command.add(audioPath == null ? "0:a?" : "1:a:0");
        command.add("-c:v");
        command.add("libx264");
        command.add("-preset");
        command.add("medium");
        command.add("-crf");
        command.add("18");
        command.add("-pix_fmt");
        command.add("yuv420p");
        command.add("-c:a");
        command.add("aac");
        command.add("-b:a");
        command.add("192k");
        command.add("-shortest");
        command.add("-movflags");
        command.add("+faststart");
        command.add(outputPath.toString());
        return command;
    }

    private String drawTextFilter(List<Map<String, Object>> overlays) {
        if (overlays == null || overlays.isEmpty()) {
            return "";
        }
        List<String> filters = new ArrayList<>();
        for (Map<String, Object> overlay : overlays) {
            String text = stringValue(overlay.get("text"), "").trim();
            if (text.isBlank()) {
                continue;
            }
            String transform = stringValue(firstNonNull(overlay.get("textTransform"), overlay.get("text_transform")), "none");
            if ("uppercase".equalsIgnoreCase(transform)) {
                text = text.toUpperCase(Locale.ROOT);
            }
            double start = Math.max(0.0, number(firstNonNull(overlay.get("startSeconds"), overlay.get("timestampSeconds")), 0.0));
            double end = Math.max(start + 0.25, number(overlay.get("endSeconds"), start + 2.0));
            double xPct = clamp(number(overlay.get("x"), 12.0), 0.0, 95.0) / 100.0;
            double yPct = clamp(number(overlay.get("y"), 68.0), 0.0, 95.0) / 100.0;
            double size = clamp(number(overlay.get("fontSize"), 30.0), 12.0, 96.0);
            String color = normalizeColor(stringValue(overlay.get("color"), "#ffffff"));
            String style = stringValue(firstNonNull(overlay.get("captionStyle"), overlay.get("caption_style")), "classic").toLowerCase(Locale.ROOT);
            String backgroundColor = normalizeColor(stringValue(firstNonNull(overlay.get("backgroundColor"), overlay.get("background_color")), "#000000"));
            double defaultOpacity = switch (style) {
                case "subtitle" -> 0.62;
                case "bubble" -> 0.92;
                case "outline" -> 0.0;
                case "glow" -> 0.18;
                default -> 0.28;
            };
            double backgroundOpacity = clamp(number(firstNonNull(overlay.get("backgroundOpacity"), overlay.get("background_opacity")), defaultOpacity), 0.0, 1.0);
            String weight = stringValue(overlay.get("fontWeight"), "900");
            boolean bold = weight.contains("700") || weight.contains("800") || weight.contains("900") || "bold".equalsIgnoreCase(weight);
            StringBuilder filter = new StringBuilder("drawtext="
                    + "text='" + escapeDrawtext(text) + "'"
                    + ":x=w*" + format(xPct)
                    + ":y=h*" + format(yPct)
                    + ":fontsize=h*" + format(size / 1200.0)
                    + ":fontcolor=" + color);
            if ("outline".equals(style)) {
                filter.append(":borderw=").append(bold ? "5" : "3")
                        .append(":bordercolor=black@0.92")
                        .append(":shadowx=2:shadowy=2:shadowcolor=black@0.75");
            } else {
                filter.append(":box=1:boxcolor=").append(backgroundColor).append("@").append(format(backgroundOpacity))
                        .append(":boxborderw=").append("bubble".equals(style) ? "22" : (bold ? "18" : "12"));
                if ("glow".equals(style)) {
                    filter.append(":shadowx=0:shadowy=0:shadowcolor=0x7c3aed@0.95");
                } else {
                    filter.append(":shadowx=1:shadowy=2:shadowcolor=black@0.65");
                }
            }
            filter.append(":enable='between(t,").append(format(start)).append(",").append(format(end)).append(")'");
            filters.add(filter.toString());
        }
        return String.join(",", filters);
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
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "FFmpeg final video render timed out.");
            }
            if (process.exitValue() != 0) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "FFmpeg final video render failed: " + tail(output, 3000));
            }
            return output;
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "FFmpeg is not available for final video rendering.", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "FFmpeg final video render was interrupted.", ex);
        }
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
        if (normalized.contains("mpeg")) return ".mp3";
        if (normalized.contains("wav")) return ".wav";
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

    private String escapeDrawtext(String value) {
        return stringValue(value, "")
                .replace("\\", "\\\\")
                .replace(":", "\\:")
                .replace("'", "\\'")
                .replace("\n", " ")
                .replace("\r", " ");
    }

    private String normalizeColor(String value) {
        String text = stringValue(value, "#ffffff").trim();
        if (text.matches("#[0-9a-fA-F]{6}")) {
            return "0x" + text.substring(1);
        }
        if (text.matches("[0-9a-fA-F]{6}")) {
            return "0x" + text;
        }
        return "white";
    }

    private Object firstNonNull(Object first, Object second) {
        return first == null ? second : first;
    }

    private double number(Object value, double fallback) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private String format(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private String tail(String value, int max) {
        String text = stringValue(value, "");
        return text.length() <= max ? text : text.substring(text.length() - max);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String stringValue(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? fallback : text;
    }

    public record RenderedVideo(String filename, String contentType, byte[] bytes, Map<String, Object> metadata) {
    }
}
