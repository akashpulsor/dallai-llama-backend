package com.dalai.llama.creator.service;

import com.dalai.llama.creator.domain.entity.CreatorAsset;
import com.dalai.llama.creator.domain.entity.CreatorShortCandidate;
import com.dalai.llama.creator.domain.entity.CreatorShortVideo;
import com.dalai.llama.creator.repository.CreatorAssetRepository;
import com.dalai.llama.creator.repository.CreatorShortCandidateRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
public class ShortPostRenderQaService {

    private static final String SOURCE = "local_ffprobe_frame_post_render_qa";

    private final AssetStorageService assetStorageService;
    private final CreatorAssetRepository assetRepository;
    private final CreatorShortCandidateRepository candidateRepository;
    private final long commandTimeoutSeconds;
    private final int commandOutputMaxChars;

    public ShortPostRenderQaService(
            AssetStorageService assetStorageService,
            CreatorAssetRepository assetRepository,
            CreatorShortCandidateRepository candidateRepository,
            @Value("${creator.shorts.post-render-qa.command-timeout-seconds:90}") long commandTimeoutSeconds,
            @Value("${creator.shorts.post-render-qa.command-output-max-chars:12000}") int commandOutputMaxChars
    ) {
        this.assetStorageService = assetStorageService;
        this.assetRepository = assetRepository;
        this.candidateRepository = candidateRepository;
        this.commandTimeoutSeconds = Math.max(5, commandTimeoutSeconds);
        this.commandOutputMaxChars = Math.max(1000, commandOutputMaxChars);
    }

    public PostRenderQaResult audit(CreatorShortVideo video, List<CreatorShortCandidate> candidates) {
        List<CreatorShortCandidate> safeCandidates = candidates == null
                ? List.of()
                : candidates.stream().filter(this::needsPostRenderQa).toList();
        List<Map<String, Object>> audits = new ArrayList<>();
        int passCount = 0;
        int warnCount = 0;
        int failCount = 0;
        for (CreatorShortCandidate candidate : safeCandidates) {
            Map<String, Object> audit = auditCandidate(video, candidate);
            audits.add(audit);
            String status = stringValue(audit.get("status"), "WARN");
            if ("PASS".equals(status)) passCount++;
            else if ("FAIL".equals(status)) failCount++;
            else warnCount++;
        }

        String status = failCount > 0 ? (passCount > 0 ? "COMPLETED_WITH_WARNINGS" : "FAILED") : (warnCount > 0 ? "COMPLETED_WITH_WARNINGS" : "COMPLETED");
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", SOURCE);
        metadata.put("generatedAt", OffsetDateTime.now().toString());
        metadata.put("candidateCount", safeCandidates.size());
        metadata.put("passedCount", passCount);
        metadata.put("warningCount", warnCount);
        metadata.put("failedCount", failCount);
        metadata.put("audits", audits);

        List<Map<String, Object>> trace = List.of(traceRow(
                "POST_RENDER_QA",
                status,
                safeCandidates.isEmpty()
                        ? "No rendered candidates needed post-render QA."
                        : "Post-render QA probed rendered MP4 dimensions, duration, audio stream, and representative frames.",
                failCount > 0 ? 0.58 : warnCount > 0 ? 0.78 : 0.92,
                metadata
        ));
        return new PostRenderQaResult(audits, trace, metadata);
    }

    private boolean needsPostRenderQa(CreatorShortCandidate candidate) {
        if (candidate == null) {
            return false;
        }
        if (candidate.getAssetId() != null) {
            return true;
        }
        Map<String, Object> renderManifest = mapValue(candidate.getRenderManifest());
        String status = stringValue(candidate.getStatus(), "").toUpperCase(Locale.ROOT);
        String renderStatus = stringValue(renderManifest.get("renderStatus"), "").toUpperCase(Locale.ROOT);
        return status.contains("RENDERED") || renderStatus.contains("RENDERED");
    }

    private Map<String, Object> auditCandidate(CreatorShortVideo video, CreatorShortCandidate candidate) {
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("source", SOURCE);
        audit.put("candidateId", candidate == null || candidate.getId() == null ? "" : candidate.getId().toString());
        audit.put("rankIndex", candidate == null ? 0 : candidate.getRankIndex());
        audit.put("timestamp", OffsetDateTime.now().toString());

        List<Map<String, Object>> issues = new ArrayList<>();
        if (candidate == null || candidate.getAssetId() == null) {
            issues.add(issue("medium", "NO_RENDERED_ASSET", "Candidate has no rendered asset id."));
            audit.put("status", "WARN");
            audit.put("issues", issues);
            persistAudit(candidate, audit);
            return audit;
        }

        CreatorAsset asset = assetRepository.findById(candidate.getAssetId()).orElse(null);
        if (asset == null) {
            issues.add(issue("high", "RENDERED_ASSET_NOT_FOUND", "Rendered asset record was not found."));
            audit.put("status", "FAIL");
            audit.put("issues", issues);
            persistAudit(candidate, audit);
            return audit;
        }

        Path workspace = null;
        try {
            workspace = Files.createTempDirectory("creator-short-post-render-qa-");
            Path file = workspace.resolve("rendered.mp4");
            assetStorageService.downloadObjectToPath(asset.getBucket(), asset.getObjectKey(), file);
            long sizeBytes = Files.size(file);
            MediaProbe probe = probe(file);
            List<Map<String, Object>> frameAudits = extractFrameAudits(file, workspace, probe.durationSeconds());
            boolean audioPresent = audioPresent(file);

            Map<String, Object> renderManifest = mapValue(candidate.getRenderManifest());
            int expectedWidth = intValue(renderManifest.get("targetWidth"), 0);
            int expectedHeight = intValue(renderManifest.get("targetHeight"), 0);
            if (sizeBytes <= 0) {
                issues.add(issue("high", "EMPTY_RENDERED_FILE", "Rendered MP4 file is empty."));
            }
            if (probe.width() <= 0 || probe.height() <= 0 || probe.durationSeconds() <= 0.0) {
                issues.add(issue("high", "INVALID_MEDIA_PROBE", "ffprobe could not read valid video dimensions or duration."));
            }
            if (expectedWidth > 0 && expectedHeight > 0 && (Math.abs(probe.width() - expectedWidth) > 2 || Math.abs(probe.height() - expectedHeight) > 2)) {
                issues.add(issue("medium", "DIMENSION_MISMATCH", "Rendered dimensions do not match the visual plan target."));
            }
            if (!audioPresent) {
                issues.add(issue("high", "NO_AUDIO_STREAM", "Rendered MP4 has no audio stream."));
            }
            long usableFrames = frameAudits.stream().filter(frame -> "PASS".equals(frame.get("status"))).count();
            if (usableFrames < Math.min(2, frameAudits.size())) {
                issues.add(issue("medium", "LOW_FRAME_QA_COVERAGE", "Representative frame extraction did not produce enough usable frames."));
            }

            String status = issues.stream().anyMatch(item -> "high".equalsIgnoreCase(stringValue(item.get("severity"), "")))
                    ? "FAIL"
                    : issues.isEmpty() ? "PASS" : "WARN";
            audit.put("status", status);
            audit.put("passed", !"FAIL".equals(status));
            audit.put("sizeBytes", sizeBytes);
            audit.put("width", probe.width());
            audit.put("height", probe.height());
            audit.put("durationSeconds", round3(probe.durationSeconds()));
            audit.put("audioPresent", audioPresent);
            audit.put("frameAudits", frameAudits);
            audit.put("issues", issues);
            persistAudit(candidate, audit);
            return audit;
        } catch (Exception ex) {
            issues.add(issue("high", "POST_RENDER_QA_FAILED", "Post-render QA failed: " + safeMessage(ex)));
            audit.put("status", "FAIL");
            audit.put("passed", false);
            audit.put("issues", issues);
            persistAudit(candidate, audit);
            return audit;
        } finally {
            deleteQuietly(workspace);
        }
    }

    private MediaProbe probe(Path file) throws IOException, InterruptedException {
        String output = run(List.of(
                "ffprobe",
                "-v", "error",
                "-select_streams", "v:0",
                "-show_entries", "stream=width,height",
                "-show_entries", "format=duration",
                "-of", "default=noprint_wrappers=1:nokey=1",
                file.toString()
        ));
        List<String> lines = output.lines().map(String::trim).filter(line -> !line.isBlank()).toList();
        int width = lines.size() > 0 ? intValue(lines.get(0), 0) : 0;
        int height = lines.size() > 1 ? intValue(lines.get(1), 0) : 0;
        double duration = lines.size() > 2 ? doubleValue(lines.get(2), 0.0) : 0.0;
        return new MediaProbe(width, height, duration);
    }

    private boolean audioPresent(Path file) {
        try {
            String output = run(List.of(
                    "ffprobe",
                    "-v", "error",
                    "-select_streams", "a",
                    "-show_entries", "stream=index",
                    "-of", "csv=p=0",
                    file.toString()
            ));
            return !output.trim().isBlank();
        } catch (Exception ignored) {
            return false;
        }
    }

    private List<Map<String, Object>> extractFrameAudits(Path file, Path workspace, double duration) {
        List<Double> timestamps = frameTimestamps(duration);
        List<Map<String, Object>> audits = new ArrayList<>();
        int index = 1;
        for (Double timestamp : timestamps) {
            Path frame = workspace.resolve("qa-frame-%02d.jpg".formatted(index));
            Map<String, Object> audit = new LinkedHashMap<>();
            audit.put("index", index);
            audit.put("timestampSeconds", round3(timestamp));
            try {
                run(List.of(
                        "ffmpeg",
                        "-hide_banner",
                        "-nostdin",
                        "-v", "error",
                        "-y",
                        "-ss", format(timestamp),
                        "-i", file.toString(),
                        "-frames:v", "1",
                        "-vf", "scale=240:-2:force_original_aspect_ratio=decrease",
                        "-q:v", "5",
                        frame.toString()
                ));
                long bytes = Files.exists(frame) ? Files.size(frame) : 0;
                audit.put("bytes", bytes);
                audit.put("status", bytes > 1024 ? "PASS" : "WARN");
            } catch (Exception ex) {
                audit.put("status", "WARN");
                audit.put("error", safeMessage(ex));
            }
            audits.add(audit);
            index++;
        }
        return audits;
    }

    private List<Double> frameTimestamps(double duration) {
        if (duration <= 0.0) {
            return List.of(0.0);
        }
        List<Double> timestamps = new ArrayList<>();
        timestamps.add(Math.min(0.5, Math.max(0.0, duration / 4.0)));
        timestamps.add(Math.max(0.0, duration / 2.0));
        timestamps.add(Math.max(0.0, duration - Math.min(0.5, duration / 5.0)));
        return timestamps.stream().sorted().distinct().toList();
    }

    private void persistAudit(CreatorShortCandidate candidate, Map<String, Object> audit) {
        if (candidate == null || candidate.getId() == null) {
            return;
        }
        Map<String, Object> renderManifest = mapValue(candidate.getRenderManifest());
        Map<String, Object> metadata = mapValue(candidate.getMetadata());
        String status = stringValue(audit.get("status"), "WARN");
        renderManifest.put("postRenderQa", audit);
        renderManifest.put("postRenderQaStatus", status);
        metadata.put("postRenderQa", audit);
        metadata.put("postRenderQaStatus", status);
        if ("FAIL".equals(status)) {
            candidate.setStatus("RENDER_QA_FAILED");
            renderManifest.put("renderStatus", "RENDER_QA_FAILED");
        } else if ("WARN".equals(status) && "RENDERED".equalsIgnoreCase(stringValue(candidate.getStatus(), ""))) {
            candidate.setStatus("RENDERED_WITH_QA_WARNINGS");
            renderManifest.put("renderStatus", "RENDERED_WITH_QA_WARNINGS");
        }
        candidate.setRenderManifest(renderManifest);
        candidate.setMetadata(metadata);
        candidateRepository.save(candidate);
    }

    private String run(List<String> command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        StringBuffer output = new StringBuffer();
        Thread outputReader = new Thread(
                () -> drainProcessOutput(process.getInputStream(), output),
                "short-post-render-qa-process-output"
        );
        outputReader.setDaemon(true);
        outputReader.start();
        boolean finished = process.waitFor(commandTimeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            outputReader.join(1000);
            throw new IOException(command.get(0) + " timed out. output=" + output);
        }
        outputReader.join(1000);
        if (process.exitValue() != 0) {
            throw new IOException(output.toString());
        }
        return output.toString();
    }

    private void drainProcessOutput(InputStream inputStream, StringBuffer output) {
        byte[] buffer = new byte[2048];
        try (InputStream input = inputStream) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                appendBounded(output, new String(buffer, 0, read, StandardCharsets.UTF_8));
            }
        } catch (IOException ex) {
            appendBounded(output, " [output read failed: " + safeMessage(ex) + "]");
        }
    }

    private void appendBounded(StringBuffer output, String chunk) {
        if (chunk == null || chunk.isEmpty() || output.length() >= commandOutputMaxChars) {
            return;
        }
        int remaining = commandOutputMaxChars - output.length();
        output.append(chunk, 0, Math.min(remaining, chunk.length()));
    }

    private void deleteQuietly(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (var stream = Files.walk(path)) {
            stream.sorted(Comparator.reverseOrder()).forEach(item -> {
                try {
                    Files.deleteIfExists(item);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    private Map<String, Object> traceRow(String stage, String status, String summary, double confidence, Map<String, Object> metadata) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("stage", stage);
        row.put("status", status);
        row.put("summary", summary);
        row.put("confidence", round3(confidence));
        row.put("agent", stage.toLowerCase(Locale.ROOT).replace('_', '-'));
        row.put("timestamp", OffsetDateTime.now().toString());
        row.put("metadata", metadata == null ? Map.of() : metadata);
        return row;
    }

    private Map<String, Object> issue(String severity, String code, String summary) {
        Map<String, Object> issue = new LinkedHashMap<>();
        issue.put("severity", severity);
        issue.put("code", code);
        issue.put("summary", summary);
        return issue;
    }

    private Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            map.forEach((key, mapValue) -> {
                if (key != null) normalized.put(String.valueOf(key), mapValue);
            });
            return normalized;
        }
        return new LinkedHashMap<>();
    }

    private String stringValue(Object value, String fallback) {
        if (value == null) return fallback;
        String text = String.valueOf(value);
        return text.isBlank() ? fallback : text;
    }

    private int intValue(Object value, int fallback) {
        if (value instanceof Number number) return number.intValue();
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value));
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }

    private double doubleValue(Object value, double fallback) {
        if (value instanceof Number number) return number.doubleValue();
        if (value != null) {
            try {
                return Double.parseDouble(String.valueOf(value));
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }

    private double round3(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    private String format(double value) {
        return String.format(Locale.US, "%.3f", Math.max(0.0, value));
    }

    private String safeMessage(Exception ex) {
        if (ex == null || ex.getMessage() == null || ex.getMessage().isBlank()) {
            return "unknown error";
        }
        return ex.getMessage().replaceAll("\\s+", " ").trim();
    }

    private record MediaProbe(int width, int height, double durationSeconds) {
    }

    public record PostRenderQaResult(
            List<Map<String, Object>> audits,
            List<Map<String, Object>> trace,
            Map<String, Object> metadata
    ) {
    }
}
