package com.dalai.llama.creator.service;

import com.dalai.llama.creator.ai.GeminiRateLimitGuard;
import com.dalai.llama.creator.ai.GeminiUsageMetadataParser;
import com.dalai.llama.creator.ai.GoogleGenAiClientFactory;
import com.dalai.llama.creator.config.CreatorProperties;
import com.dalai.llama.creator.domain.entity.CreatorAsset;
import com.dalai.llama.creator.domain.entity.CreatorShortVideo;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.net.URI;
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
import java.util.concurrent.TimeUnit;

@Service
public class GoogleShortVideoUnderstandingService {

    private static final Logger log = LoggerFactory.getLogger(GoogleShortVideoUnderstandingService.class);
    private static final String PROMPT_TYPE = "SHORTS_VIDEO_UNDERSTANDING";
    private static final int MAX_INLINE_VIDEO_BYTES = 18 * 1024 * 1024;
    private static final int RESPONSE_MAX_IN_MEMORY_BYTES = 48 * 1024 * 1024;
    private static final long FILE_API_POLL_TIMEOUT_MS = 180_000;
    private static final long FILE_API_POLL_INTERVAL_MS = 2_000;
    private static final int MAX_ANALYSIS_SAMPLE_SECONDS = 90;
    private static final int FALLBACK_ANALYSIS_SAMPLE_SECONDS = 45;
    private static final Duration SAMPLE_COMMAND_TIMEOUT = Duration.ofMinutes(4);

    private final CreatorProperties properties;
    private final CreatorAiPricingService pricingService;
    private final GeminiUsageMetadataParser usageMetadataParser;
    private final GoogleGenAiClientFactory googleGenAiClientFactory;
    private final GeminiRateLimitGuard geminiRateLimitGuard;
    private final ObjectMapper objectMapper;

    public GoogleShortVideoUnderstandingService(
            CreatorProperties properties,
            CreatorAiPricingService pricingService,
            GeminiUsageMetadataParser usageMetadataParser,
            GoogleGenAiClientFactory googleGenAiClientFactory,
            GeminiRateLimitGuard geminiRateLimitGuard,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.pricingService = pricingService;
        this.usageMetadataParser = usageMetadataParser;
        this.googleGenAiClientFactory = googleGenAiClientFactory;
        this.geminiRateLimitGuard = geminiRateLimitGuard;
        this.objectMapper = objectMapper;
    }

    public ShortVideoUnderstandingResult analyze(
            CreatorShortVideo video,
            CreatorAsset sourceAsset,
            Path sourceVideoPath,
            long sourceSizeBytes,
            String contentType
    ) {
        String model = stringValue(properties.getAi().getGeminiModel(), "gemini-2.5-flash");
        if (sourceVideoPath == null || !Files.exists(sourceVideoPath) || sourceSizeBytes <= 0) {
            return skipped(model, "FAILED", "Uploaded video file was not available for Google transcript analysis.");
        }

        PreparedVideoInput preparedInput = prepareAnalysisInput(sourceVideoPath, sourceSizeBytes, contentType);
        if (preparedInput.bytes() == null || preparedInput.bytes().length == 0) {
            return skipped(
                    model,
                    "SKIPPED",
                    stringValue(preparedInput.metadata().get("reason"), "Could not prepare a bounded analysis sample for Google video understanding.")
            );
        }

        String prompt = buildPrompt(video, sourceAsset, preparedInput);
        Map<String, Object> request;
        if (preparedInput.bytes().length > MAX_INLINE_VIDEO_BYTES) {
            if (googleGenAiClientFactory.useVertexAi()) {
                return skipped(model, "SKIPPED", "Prepared analysis sample is still too large for inline Gemini input and Files API upload is only enabled for AI Studio in this service version. Size bytes: " + preparedInput.bytes().length + ".");
            }
            UploadedVideoFile uploadedFile = uploadVideoFile(model, video, preparedInput.bytes(), preparedInput.contentType());
            request = buildFileRequest(prompt, uploadedFile.fileUri(), uploadedFile.mimeType());
        } else {
            request = buildRequest(prompt, preparedInput.bytes(), preparedInput.contentType());
        }
        try {
            Map<String, Object> response = geminiRateLimitGuard.execute(PROMPT_TYPE, model, () ->
                    googleGenAiClientFactory.client(RESPONSE_MAX_IN_MEMORY_BYTES)
                            .post()
                            .uri(googleGenAiClientFactory.generateContentUri(model))
                            .bodyValue(request)
                            .retrieve()
                            .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {
                            })
                            .block(Duration.ofMillis(properties.getAi().getTimeoutMs()))
            );
            return normalizeResponse(model, prompt, response == null ? Map.of() : response);
        } catch (Exception ex) {
            log.warn("Google shorts video understanding failed videoId={} model={} message={}", video == null ? null : video.getId(), model, ex.getMessage());
            return skipped(model, "FAILED", "Google video understanding failed: " + ex.getMessage());
        }
    }

    private Map<String, Object> buildRequest(String prompt, byte[] videoBytes, String contentType) {
        List<Map<String, Object>> parts = new ArrayList<>();
        parts.add(Map.of("text", prompt));
        parts.add(Map.of("inline_data", Map.of(
                "mime_type", normalizeVideoContentType(contentType),
                "data", Base64.getEncoder().encodeToString(videoBytes)
        )));

        Map<String, Object> generationConfig = new LinkedHashMap<>();
        generationConfig.put("responseMimeType", "application/json");
        if (properties.getAi().getMaxOutputTokens() != null && properties.getAi().getMaxOutputTokens() > 0) {
            generationConfig.put("maxOutputTokens", properties.getAi().getMaxOutputTokens());
        }

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("systemInstruction", Map.of(
                "parts", List.of(Map.of("text", "Return only one valid JSON object. Do not wrap it in markdown."))
        ));
        request.put("contents", List.of(Map.of(
                "role", "user",
                "parts", parts
        )));
        request.put("generationConfig", generationConfig);
        return request;
    }

    private Map<String, Object> buildFileRequest(String prompt, String fileUri, String contentType) {
        List<Map<String, Object>> parts = new ArrayList<>();
        parts.add(Map.of("text", prompt));
        parts.add(Map.of("file_data", Map.of(
                "mime_type", normalizeVideoContentType(contentType),
                "file_uri", fileUri
        )));
        return buildGenerateContentRequest(parts);
    }
    private Map<String, Object> buildGenerateContentRequest(List<Map<String, Object>> parts) {
        Map<String, Object> generationConfig = new LinkedHashMap<>();
        generationConfig.put("responseMimeType", "application/json");
        if (properties.getAi().getMaxOutputTokens() != null && properties.getAi().getMaxOutputTokens() > 0) {
            generationConfig.put("maxOutputTokens", properties.getAi().getMaxOutputTokens());
        }

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("systemInstruction", Map.of(
                "parts", List.of(Map.of("text", "Return only one valid JSON object. Do not wrap it in markdown."))
        ));
        request.put("contents", List.of(Map.of(
                "role", "user",
                "parts", parts
        )));
        request.put("generationConfig", generationConfig);
        return request;
    }

    private PreparedVideoInput prepareAnalysisInput(Path sourcePath, long sourceBytes, String contentType) {
        if (sourcePath == null || !Files.exists(sourcePath) || sourceBytes <= 0) {
            return new PreparedVideoInput(new byte[0], normalizeVideoContentType(contentType), "missing", 0, Map.of("reason", "source video file was empty or missing"));
        }
        if (sourceBytes <= MAX_INLINE_VIDEO_BYTES) {
            try {
                byte[] bytes = Files.readAllBytes(sourcePath);
                return new PreparedVideoInput(
                        bytes,
                        normalizeVideoContentType(contentType),
                        "complete_small_upload",
                        0,
                        Map.of(
                                "reason", "source video was below inline analysis limit",
                                "originalBytes", sourceBytes,
                                "bytesSentToGoogle", bytes.length
                        )
                );
            } catch (IOException ex) {
                return new PreparedVideoInput(new byte[0], normalizeVideoContentType(contentType), "read_failed", 0, Map.of(
                        "reason", ex.getMessage() == null ? "could not read source video file" : ex.getMessage(),
                        "originalBytes", sourceBytes
                ));
            }
        }

        Path workspace = null;
        try {
            workspace = Files.createTempDirectory("creator-shorts-understanding-");
            return prepareLargeSourceSample(sourcePath, sourceBytes, contentType, workspace);
        } catch (Exception ex) {
            log.warn("Could not prepare shorts analysis sample from sourcePath={} bytes={} message={}", sourcePath, sourceBytes, ex.getMessage());
            return new PreparedVideoInput(new byte[0], normalizeVideoContentType(contentType), "sample_failed", 0, Map.of(
                    "reason", "Could not prepare a bounded analysis sample for Google video understanding: " + (ex.getMessage() == null ? "sample generation failed" : ex.getMessage()),
                    "originalBytes", sourceBytes
            ));
        } finally {
            deleteQuietly(workspace);
        }
    }

    private PreparedVideoInput prepareAnalysisInput(byte[] sourceVideoBytes, String contentType) {
        if (sourceVideoBytes == null || sourceVideoBytes.length == 0) {
            return new PreparedVideoInput(new byte[0], normalizeVideoContentType(contentType), "missing", 0, Map.of("reason", "source video bytes were empty"));
        }
        if (sourceVideoBytes.length <= MAX_INLINE_VIDEO_BYTES) {
            return new PreparedVideoInput(
                    sourceVideoBytes,
                    normalizeVideoContentType(contentType),
                    "complete_small_upload",
                    0,
                    Map.of(
                            "reason", "source video was below inline analysis limit",
                            "originalBytes", sourceVideoBytes.length,
                            "bytesSentToGoogle", sourceVideoBytes.length
                    )
            );
        }

        Path workspace = null;
        try {
            workspace = Files.createTempDirectory("creator-shorts-understanding-");
            Path sourcePath = workspace.resolve("source" + extensionFor(contentType));
            Files.write(sourcePath, sourceVideoBytes);
            return prepareLargeSourceSample(sourcePath, sourceVideoBytes.length, contentType, workspace);
        } catch (Exception ex) {
            log.warn("Could not prepare shorts analysis sample, falling back to skipped media understanding bytes={} message={}", sourceVideoBytes.length, ex.getMessage());
            return new PreparedVideoInput(new byte[0], normalizeVideoContentType(contentType), "sample_failed", 0, Map.of(
                    "reason", "Could not prepare a bounded analysis sample for Google video understanding: " + (ex.getMessage() == null ? "sample generation failed" : ex.getMessage()),
                    "originalBytes", sourceVideoBytes.length
            ));
        } finally {
            deleteQuietly(workspace);
        }
    }

    private PreparedVideoInput prepareLargeSourceSample(Path sourcePath, long sourceBytes, String contentType, Path workspace) throws IOException {
        double durationSeconds = probeDurationSeconds(sourcePath);
        List<Double> starts = sampleStartCandidates(durationSeconds);
        List<String> failures = new ArrayList<>();
        int attempt = 1;
        for (Double startSeconds : starts) {
            Path primary = workspace.resolve("analysis-sample-%02d.mp4".formatted(attempt++));
            try {
                extractAnalysisSample(sourcePath, primary, startSeconds, MAX_ANALYSIS_SAMPLE_SECONDS, 720, 12, 34, true);
                return preparedSample(primary, sourceBytes, durationSeconds, startSeconds, MAX_ANALYSIS_SAMPLE_SECONDS, "ffmpeg_temporal_sample", "source video exceeded inline analysis limit; sent bounded ffmpeg sample to Google", failures);
            } catch (Exception ex) {
                failures.add("temporal sample at " + format(startSeconds) + "s failed: " + compact(ex.getMessage(), 240));
            }

            Path visualOnly = workspace.resolve("analysis-visual-sample-%02d.mp4".formatted(attempt++));
            try {
                extractAnalysisSample(sourcePath, visualOnly, startSeconds, FALLBACK_ANALYSIS_SAMPLE_SECONDS, 480, 8, 38, false);
                return preparedSample(visualOnly, sourceBytes, durationSeconds, startSeconds, FALLBACK_ANALYSIS_SAMPLE_SECONDS, "ffmpeg_visual_only_fallback", "primary audio/video sample failed; sent compact visual-only sample to Google", failures);
            } catch (Exception ex) {
                failures.add("visual-only sample at " + format(startSeconds) + "s failed: " + compact(ex.getMessage(), 240));
            }
        }
        throw new IllegalStateException("all bounded sample attempts failed: " + compact(String.join(" | ", failures), 1200));
    }

    private PreparedVideoInput preparedSample(
            Path samplePath,
            long sourceBytes,
            double durationSeconds,
            double startSeconds,
            int sampleSeconds,
            String source,
            String reason,
            List<String> priorFailures
    ) throws IOException {
        byte[] sampleBytes = Files.readAllBytes(samplePath);
        if (sampleBytes.length == 0) {
            throw new IllegalStateException("ffmpeg produced an empty analysis sample.");
        }
        if (sampleBytes.length > MAX_INLINE_VIDEO_BYTES && googleGenAiClientFactory.useVertexAi()) {
            throw new IllegalStateException("prepared sample is still too large for Vertex inline input: " + sampleBytes.length + " bytes.");
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("reason", reason);
        metadata.put("originalBytes", sourceBytes);
        metadata.put("sourceDurationSeconds", round3(durationSeconds));
        metadata.put("sampleStartSeconds", round3(startSeconds));
        metadata.put("sampleSeconds", sampleSeconds);
        metadata.put("bytesSentToGoogle", sampleBytes.length);
        metadata.put("priorSampleFailures", priorFailures == null ? List.of() : new ArrayList<>(priorFailures));
        return new PreparedVideoInput(sampleBytes, "video/mp4", source, sampleSeconds, metadata);
    }

    private void extractAnalysisSample(
            Path sourcePath,
            Path samplePath,
            double startSeconds,
            int sampleSeconds,
            int width,
            int fps,
            int crf,
            boolean includeAudio
    ) {
        List<String> command = new ArrayList<>();
        command.add("ffmpeg");
        command.add("-hide_banner");
        command.add("-v");
        command.add("error");
        command.add("-y");
        if (startSeconds > 0.1) {
            command.add("-ss");
            command.add(format(startSeconds));
        }
        command.add("-i");
        command.add(sourcePath.toString());
        command.add("-t");
        command.add(String.valueOf(sampleSeconds));
        command.add("-map");
        command.add("0:v:0");
        if (includeAudio) {
            command.add("-map");
            command.add("0:a?");
        }
        command.add("-sn");
        command.add("-dn");
        command.add("-vf");
        command.add(evenDimensionVideoFilter(width, fps));
        command.add("-c:v");
        command.add("libx264");
        command.add("-preset");
        command.add("ultrafast");
        command.add("-crf");
        command.add(String.valueOf(crf));
        command.add("-pix_fmt");
        command.add("yuv420p");
        if (includeAudio) {
            command.add("-c:a");
            command.add("aac");
            command.add("-b:a");
            command.add("48k");
            command.add("-ac");
            command.add("1");
            command.add("-ar");
            command.add("16000");
        } else {
            command.add("-an");
        }
        command.add("-movflags");
        command.add("+faststart");
        command.add(samplePath.toString());
        runSampleCommand(command);
    }

    private String evenDimensionVideoFilter(int width, int fps) {
        int safeWidth = Math.max(2, width);
        int safeFps = Math.max(1, fps);
        return "fps=" + safeFps
                + ",scale=" + safeWidth + ":-2:force_original_aspect_ratio=decrease"
                + ",pad=ceil(iw/2)*2:ceil(ih/2)*2"
                + ",setsar=1";
    }

    private double probeDurationSeconds(Path sourcePath) {
        try {
            String output = runCommand(List.of(
                    "ffprobe",
                    "-v", "error",
                    "-show_entries", "format=duration",
                    "-of", "default=noprint_wrappers=1:nokey=1",
                    sourcePath.toString()
            ), Duration.ofSeconds(45), "ffprobe duration").trim();
            return doubleValue(output, 0.0);
        } catch (Exception ex) {
            log.info("Could not probe source video duration for understanding sample: {}", ex.getMessage());
            return 0.0;
        }
    }

    private List<Double> sampleStartCandidates(double durationSeconds) {
        java.util.TreeSet<Double> starts = new java.util.TreeSet<>();
        starts.add(0.0);
        if (durationSeconds > FALLBACK_ANALYSIS_SAMPLE_SECONDS + 5) {
            double maxStart = Math.max(0.0, durationSeconds - FALLBACK_ANALYSIS_SAMPLE_SECONDS);
            starts.add(Math.min(maxStart, 60.0));
            starts.add(Math.min(maxStart, Math.max(0.0, durationSeconds * 0.25)));
            starts.add(Math.min(maxStart, Math.max(0.0, durationSeconds * 0.50)));
            starts.add(maxStart);
        } else {
            starts.add(30.0);
            starts.add(120.0);
        }
        return starts.stream().limit(5).toList();
    }

    private void runSampleCommand(List<String> command) {
        runCommand(command, SAMPLE_COMMAND_TIMEOUT, "ffmpeg analysis sample");
    }

    private String runCommand(List<String> command, Duration timeout, String label) {
        try {
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(timeout.toSeconds(), TimeUnit.SECONDS);
            String output = new String(process.getInputStream().readAllBytes());
            if (!finished) {
                process.destroyForcibly();
                throw new IllegalStateException(label + " timed out.");
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException(label + " failed: " + tail(output, 2000));
            }
            return output;
        } catch (IOException ex) {
            throw new IllegalStateException(label + " is not available.", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(label + " was interrupted.", ex);
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

    private String extensionFor(String contentType) {
        String normalized = normalizeVideoContentType(contentType);
        if (normalized.contains("quicktime")) return ".mov";
        if (normalized.contains("webm")) return ".webm";
        if (normalized.contains("x-matroska")) return ".mkv";
        return ".mp4";
    }

    private String tail(String value, int maxLength) {
        String safe = value == null ? "" : value;
        return safe.length() <= maxLength ? safe : safe.substring(safe.length() - maxLength);
    }
    private UploadedVideoFile uploadVideoFile(String model, CreatorShortVideo video, byte[] videoBytes, String contentType) {
        WebClient client = googleGenAiClientFactory.client(RESPONSE_MAX_IN_MEMORY_BYTES);
        String uploadEndpoint = googleGenAiClientFactory.baseUrl().replaceFirst("/v1beta/?$", "/upload/v1beta") + "/files";
        String mimeType = normalizeVideoContentType(contentType);
        String displayName = "shorts-" + (video == null || video.getId() == null ? java.util.UUID.randomUUID() : video.getId());

        ResponseEntity<Void> startResponse = client
                .post()
                .uri(URI.create(uploadEndpoint))
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Goog-Upload-Protocol", "resumable")
                .header("X-Goog-Upload-Command", "start")
                .header("X-Goog-Upload-Header-Content-Length", String.valueOf(videoBytes.length))
                .header("X-Goog-Upload-Header-Content-Type", mimeType)
                .bodyValue(Map.of("file", Map.of("display_name", displayName)))
                .retrieve()
                .toBodilessEntity()
                .block(Duration.ofMillis(properties.getAi().getTimeoutMs()));

        String uploadUrl = startResponse == null ? "" : startResponse.getHeaders().getFirst("x-goog-upload-url");
        if (uploadUrl == null || uploadUrl.isBlank()) {
            uploadUrl = startResponse == null ? "" : startResponse.getHeaders().getFirst("X-Goog-Upload-URL");
        }
        if (uploadUrl == null || uploadUrl.isBlank()) {
            throw new IllegalStateException("Gemini Files API did not return an upload URL.");
        }

        Map<String, Object> uploadResponse = client
                .post()
                .uri(URI.create(uploadUrl))
                .contentType(MediaType.parseMediaType(mimeType))
                .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(videoBytes.length))
                .header("X-Goog-Upload-Offset", "0")
                .header("X-Goog-Upload-Command", "upload, finalize")
                .bodyValue(videoBytes)
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {
                })
                .block(Duration.ofMillis(Math.max(properties.getAi().getTimeoutMs(), FILE_API_POLL_TIMEOUT_MS)));

        Map<String, Object> file = mapValue(uploadResponse == null ? null : uploadResponse.get("file"));
        if (file.isEmpty() && uploadResponse != null) {
            file = mapValue(uploadResponse);
        }
        file = waitForActiveFile(client, file);
        String fileUri = stringValue(file.get("uri"), "");
        if (fileUri.isBlank()) {
            throw new IllegalStateException("Gemini Files API upload completed without a file URI.");
        }
        log.info("Uploaded shorts source video to Gemini Files API model={} videoId={} fileName={} state={}",
                model,
                video == null ? null : video.getId(),
                file.get("name"),
                file.get("state"));
        return new UploadedVideoFile(fileUri, mimeType, stringValue(file.get("name"), ""));
    }

    private Map<String, Object> waitForActiveFile(WebClient client, Map<String, Object> file) {
        String name = stringValue(file.get("name"), "");
        if (name.isBlank()) {
            return file;
        }
        long deadline = System.currentTimeMillis() + FILE_API_POLL_TIMEOUT_MS;
        Map<String, Object> current = new LinkedHashMap<>(file);
        while (System.currentTimeMillis() < deadline) {
            String state = stringValue(current.get("state"), "");
            if (state.isBlank() || "ACTIVE".equalsIgnoreCase(state)) {
                return current;
            }
            if ("FAILED".equalsIgnoreCase(state)) {
                throw new IllegalStateException("Gemini Files API marked uploaded video as FAILED.");
            }
            try {
                Thread.sleep(FILE_API_POLL_INTERVAL_MS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for Gemini file processing.", ex);
            }
            Map<String, Object> getResponse = client
                    .get()
                    .uri("/" + name.replaceAll("^/+", ""))
                    .retrieve()
                    .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {
                    })
                    .block(Duration.ofMillis(properties.getAi().getTimeoutMs()));
            current = mapValue(getResponse == null ? null : getResponse.get("file"));
            if (current.isEmpty() && getResponse != null) {
                current = mapValue(getResponse);
            }
        }
        throw new IllegalStateException("Timed out waiting for Gemini Files API to process uploaded video.");
    }

    private String buildPrompt(CreatorShortVideo video, CreatorAsset sourceAsset, PreparedVideoInput preparedInput) {
        return """
                Analyze the attached source video for a short-video generation system.

                Required stages to perform for real from the media:
                1. TRANSCRIPT
                2. TRANSCRIPT_CRITIC
                3. VIDEO_TYPE_CLASSIFICATION
                4. VIDEO_TYPE_CRITIC

                Source metadata:
                {
                  "shortVideoId": "%s",
                  "title": "%s",
                  "originalFileName": "%s",
                  "platform": "%s",
                  "targetDurationSeconds": %s,
                  "requestedShorts": %s,
                  "assetId": "%s",
                  "contentType": "%s",
                  "analysisInputSource": "%s",
                  "analysisSampleSeconds": %s,
                  "analysisBytesSent": %s,
                  "analysisSamplingReason": "%s"
                }

                Return JSON with this exact top-level shape:
                {
                  "transcript": [
                    {
                      "id": "n-001",
                      "start": 0.0,
                      "end": 12.4,
                      "time": "00:00 - 00:12",
                      "speaker": "Speaker 1",
                      "transcript": "verbatim or near-verbatim speech",
                      "sceneId": "scene-001",
                      "emotion": 0,
                      "motion": 0,
                      "interestingness": 0,
                      "frames": ["F01"]
                    }
                  ],
                  "transcriptCritic": {
                    "status": "PASS|WARN|FAIL",
                    "confidence": 0.0,
                    "issues": [],
                    "coverageEstimate": "full|partial|low_audio|no_speech",
                    "summary": "short assessment"
                  },
                  "videoDna": {
                    "primaryType": "podcast|interview|tutorial|sketch|documentary|reaction|gaming|presentation|review|vlog|unknown",
                    "confidence": 0.0,
                    "structureType": "qa|dramatic|instructional|storytelling|reveal|reaction|unknown",
                    "analysisSource": "gemini_video_inline",
                    "reason": "why this type was selected"
                  },
                  "videoTypeCritic": {
                    "status": "PASS|WARN|FAIL",
                    "confidence": 0.0,
                    "alternatives": [],
                    "issues": [],
                    "summary": "short assessment"
                  },
                  "trace": [
                    {"stage": "TRANSCRIPT", "status": "COMPLETED", "summary": "...", "confidence": 0.0},
                    {"stage": "TRANSCRIPT_CRITIC", "status": "COMPLETED", "summary": "...", "confidence": 0.0},
                    {"stage": "VIDEO_TYPE_CLASSIFICATION", "status": "COMPLETED", "summary": "...", "confidence": 0.0},
                    {"stage": "VIDEO_TYPE_CRITIC", "status": "COMPLETED", "summary": "...", "confidence": 0.0}
                  ]
                }

                Segment transcript into useful semantic nodes. Use seconds for start/end. If audio is unclear, return the best possible partial transcript and mark critic issues.
                """.formatted(
                video == null || video.getId() == null ? "" : video.getId(),
                video == null ? "" : escape(video.getTitle()),
                video == null ? "" : escape(video.getOriginalFileName()),
                video == null ? "" : escape(video.getPlatform()),
                video == null ? 60 : video.getTargetDurationSeconds(),
                video == null ? 20 : video.getRequestedShorts(),
                sourceAsset == null || sourceAsset.getId() == null ? "" : sourceAsset.getId(),
                sourceAsset == null ? "" : escape(sourceAsset.getContentType()),
                escape(preparedInput.source()),
                preparedInput.sampleSeconds(),
                preparedInput.bytes() == null ? 0 : preparedInput.bytes().length,
                escape(stringValue(preparedInput.metadata().get("reason"), ""))
        );
    }

    private ShortVideoUnderstandingResult normalizeResponse(String model, String prompt, Map<String, Object> response) {
        String rawText = outputText(response);
        Map<String, Object> output = parseJsonObject(rawText);
        Map<String, Object> usage = usageMetadataParser.parse(response.get("usageMetadata"));
        long fallbackInputTokens = pricingService.estimateTextTokens(prompt);
        long inputTokens = Math.max(longValue(usage.get("inputTokens")), fallbackInputTokens);
        long outputTokens = longValue(usage.get("outputTokens"));
        String usageSource = longValue(usage.get("inputTokens")) > 0 || outputTokens > 0 ? "PROVIDER" : "ESTIMATED_TEXT_ONLY";

        Map<String, Object> tokenMetadata = new LinkedHashMap<>(usage);
        tokenMetadata.put("inputTokens", inputTokens);
        tokenMetadata.put("outputTokens", outputTokens);
        tokenMetadata.put("totalTokens", Math.max(longValue(usage.get("totalTokens")), inputTokens + outputTokens));
        tokenMetadata.put("source", usageSource);
        tokenMetadata.put("fallbackTextInputTokens", fallbackInputTokens);

        Map<String, Object> costMetadata = pricingService.estimateTextCall(
                "gemini",
                model,
                PROMPT_TYPE,
                inputTokens,
                outputTokens,
                usageSource
        );
        costMetadata.put("provider", "gemini");
        costMetadata.put("model", model);
        costMetadata.put("googleGenaiBackend", googleGenAiClientFactory.backend());
        costMetadata.put("promptType", PROMPT_TYPE);

        Map<String, Object> videoDna = mapValue(output.get("videoDna"));
        videoDna.putIfAbsent("analysisSource", "gemini_video_inline");
        List<Map<String, Object>> transcript = normalizeTranscript(listOfMaps(output.get("transcript")));
        Map<String, Object> transcriptCritic = defaultCritic(mapValue(output.get("transcriptCritic")), !transcript.isEmpty(), "Transcript generated from Google Gemini video input.");
        Map<String, Object> videoTypeCritic = defaultCritic(mapValue(output.get("videoTypeCritic")), !videoDna.isEmpty(), "Video type classification generated from Google Gemini video input.");
        List<Map<String, Object>> trace = normalizeTrace(listOfMaps(output.get("trace")), transcript, videoDna, transcriptCritic, videoTypeCritic);

        Map<String, Object> rawOutput = new LinkedHashMap<>(output);
        rawOutput.put("rawTextPreview", compact(rawText, 4000));
        rawOutput.put("rawTextLength", rawText == null ? 0 : rawText.length());

        return new ShortVideoUnderstandingResult(
                true,
                "gemini",
                model,
                videoDna,
                transcript,
                transcriptCritic,
                videoTypeCritic,
                trace,
                tokenMetadata,
                costMetadata,
                rawOutput
        );
    }

    private ShortVideoUnderstandingResult skipped(String model, String status, String reason) {
        Map<String, Object> videoDna = new LinkedHashMap<>();
        videoDna.put("primaryType", "unknown");
        videoDna.put("confidence", 0);
        videoDna.put("structureType", "unknown");
        videoDna.put("analysisSource", "upload_metadata");
        videoDna.put("reason", reason);

        Map<String, Object> transcriptCritic = Map.of(
                "status", status,
                "confidence", 0,
                "issues", List.of(reason),
                "coverageEstimate", "not_available",
                "summary", reason
        );
        Map<String, Object> videoTypeCritic = Map.of(
                "status", status,
                "confidence", 0,
                "issues", List.of(reason),
                "summary", reason
        );
        List<Map<String, Object>> trace = List.of(
                traceRow("TRANSCRIPT", status, reason, 0),
                traceRow("TRANSCRIPT_CRITIC", status, reason, 0),
                traceRow("VIDEO_TYPE_CLASSIFICATION", status, reason, 0),
                traceRow("VIDEO_TYPE_CRITIC", status, reason, 0)
        );
        return new ShortVideoUnderstandingResult(
                false,
                "gemini",
                model,
                videoDna,
                List.of(),
                transcriptCritic,
                videoTypeCritic,
                trace,
                Map.of("source", "SKIPPED"),
                Map.of("totalCost", java.math.BigDecimal.ZERO, "billableTotalCost", java.math.BigDecimal.ZERO, "customerTotalCost", java.math.BigDecimal.ZERO),
                Map.of("reason", reason)
        );
    }

    private List<Map<String, Object>> normalizeTranscript(List<Map<String, Object>> transcript) {
        List<Map<String, Object>> normalized = new ArrayList<>();
        int index = 1;
        for (Map<String, Object> row : transcript) {
            Map<String, Object> item = new LinkedHashMap<>(row);
            item.putIfAbsent("id", "n-%03d".formatted(index));
            item.putIfAbsent("speaker", "Speaker 1");
            item.putIfAbsent("sceneId", "scene-%03d".formatted(Math.max(1, index)));
            item.putIfAbsent("frames", List.of("F%02d".formatted(index)));
            double start = doubleValue(item.get("start"), Math.max(0, (index - 1) * 12));
            double end = doubleValue(item.get("end"), Math.max(start + 1, index * 12));
            item.put("start", start);
            item.put("end", end);
            item.putIfAbsent("time", timeLabel(start, end));
            item.putIfAbsent("emotion", 0);
            item.putIfAbsent("motion", 0);
            item.putIfAbsent("interestingness", 0);
            normalized.add(item);
            index++;
        }
        return normalized;
    }

    private List<Map<String, Object>> normalizeTrace(
            List<Map<String, Object>> trace,
            List<Map<String, Object>> transcript,
            Map<String, Object> videoDna,
            Map<String, Object> transcriptCritic,
            Map<String, Object> videoTypeCritic
    ) {
        Map<String, Map<String, Object>> byStage = new LinkedHashMap<>();
        for (Map<String, Object> row : trace) {
            Map<String, Object> normalized = new LinkedHashMap<>(row);
            String stage = stringValue(normalized.get("stage"), "").toUpperCase(Locale.ROOT);
            if (stage.isBlank()) {
                continue;
            }
            normalized.put("stage", stage);
            normalized.putIfAbsent("status", "COMPLETED");
            normalized.putIfAbsent("agent", stage.toLowerCase(Locale.ROOT).replace('_', '-'));
            normalized.putIfAbsent("timestamp", OffsetDateTime.now().toString());
            byStage.put(stage, normalized);
        }
        byStage.putIfAbsent("TRANSCRIPT", traceRow(
                "TRANSCRIPT",
                transcript.isEmpty() ? "WARN" : "COMPLETED",
                transcript.isEmpty() ? "Google did not return transcript nodes." : "Google returned " + transcript.size() + " transcript nodes from the uploaded video.",
                transcript.isEmpty() ? 0 : 0.8
        ));
        byStage.putIfAbsent("TRANSCRIPT_CRITIC", traceRow(
                "TRANSCRIPT_CRITIC",
                stringValue(transcriptCritic.get("status"), "COMPLETED"),
                stringValue(transcriptCritic.get("summary"), "Transcript critic completed."),
                doubleValue(transcriptCritic.get("confidence"), 0.7)
        ));
        byStage.putIfAbsent("VIDEO_TYPE_CLASSIFICATION", traceRow(
                "VIDEO_TYPE_CLASSIFICATION",
                videoDna.isEmpty() ? "WARN" : "COMPLETED",
                "Classified video as " + stringValue(videoDna.get("primaryType"), "unknown") + ".",
                doubleValue(videoDna.get("confidence"), 0.7)
        ));
        byStage.putIfAbsent("VIDEO_TYPE_CRITIC", traceRow(
                "VIDEO_TYPE_CRITIC",
                stringValue(videoTypeCritic.get("status"), "COMPLETED"),
                stringValue(videoTypeCritic.get("summary"), "Video type critic completed."),
                doubleValue(videoTypeCritic.get("confidence"), 0.7)
        ));
        return List.of(
                byStage.get("TRANSCRIPT"),
                byStage.get("TRANSCRIPT_CRITIC"),
                byStage.get("VIDEO_TYPE_CLASSIFICATION"),
                byStage.get("VIDEO_TYPE_CRITIC")
        );
    }

    private Map<String, Object> defaultCritic(Map<String, Object> critic, boolean pass, String summary) {
        Map<String, Object> result = new LinkedHashMap<>(critic);
        result.putIfAbsent("status", pass ? "PASS" : "WARN");
        result.putIfAbsent("confidence", pass ? 0.75 : 0.25);
        result.putIfAbsent("issues", List.of());
        result.putIfAbsent("summary", summary);
        return result;
    }

    private Map<String, Object> traceRow(String stage, String status, String summary, double confidence) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("stage", stage);
        row.put("status", status);
        row.put("summary", summary);
        row.put("confidence", confidence);
        row.put("agent", stage.toLowerCase(Locale.ROOT).replace('_', '-'));
        row.put("timestamp", OffsetDateTime.now().toString());
        return row;
    }

    private String outputText(Map<String, Object> response) {
        Object candidates = response.get("candidates");
        if (candidates instanceof List<?> candidateItems) {
            StringBuilder text = new StringBuilder();
            for (Object candidateItem : candidateItems) {
                Map<String, Object> candidate = mapValue(candidateItem);
                Map<String, Object> content = mapValue(candidate.get("content"));
                Object parts = content.get("parts");
                if (parts instanceof List<?> partItems) {
                    for (Object partItem : partItems) {
                        Map<String, Object> part = mapValue(partItem);
                        Object partText = part.get("text");
                        if (partText != null && !String.valueOf(partText).isBlank()) {
                            text.append(partText);
                        }
                    }
                }
            }
            return text.toString();
        }
        return "";
    }

    private Map<String, Object> parseJsonObject(String outputText) {
        if (outputText == null || outputText.isBlank()) {
            return new LinkedHashMap<>();
        }
        String cleaned = stripJsonFence(outputText.trim());
        try {
            return objectMapper.readValue(cleaned, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (Exception ex) {
            String objectJson = firstBalancedJsonObject(cleaned);
            if (!objectJson.isBlank()) {
                try {
                    return objectMapper.readValue(objectJson, new TypeReference<LinkedHashMap<String, Object>>() {
                    });
                } catch (Exception ignored) {
                    return new LinkedHashMap<>();
                }
            }
            return new LinkedHashMap<>();
        }
    }

    private String firstBalancedJsonObject(String text) {
        int start = text == null ? -1 : text.indexOf('{');
        if (start < 0) {
            return "";
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int index = start; index < text.length(); index++) {
            char character = text.charAt(index);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (character == '\\' && inString) {
                escaped = true;
                continue;
            }
            if (character == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (character == '{') {
                depth++;
            } else if (character == '}') {
                depth--;
                if (depth == 0) {
                    return text.substring(start, index + 1);
                }
            }
        }
        return "";
    }

    private String stripJsonFence(String text) {
        String cleaned = text == null ? "" : text.trim();
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceFirst("^```[A-Za-z0-9_-]*\\s*", "");
            cleaned = cleaned.replaceFirst("\\s*```$", "");
        }
        return cleaned.trim();
    }

    private String normalizeVideoContentType(String contentType) {
        String safe = stringValue(contentType, "video/mp4").toLowerCase(Locale.ROOT);
        if (safe.startsWith("video/")) {
            return safe;
        }
        return "video/mp4";
    }

    private Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            map.forEach((key, item) -> {
                if (key != null) {
                    normalized.put(String.valueOf(key), item);
                }
            });
            return normalized;
        }
        return new LinkedHashMap<>();
    }

    private List<Map<String, Object>> listOfMaps(Object value) {
        if (value instanceof List<?> list) {
            List<Map<String, Object>> result = new ArrayList<>();
            for (Object item : list) {
                Map<String, Object> map = mapValue(item);
                if (!map.isEmpty()) {
                    result.add(map);
                }
            }
            return result;
        }
        return new ArrayList<>();
    }

    private long longValue(Object value) {
        if (value instanceof Number number) {
            return Math.max(0, number.longValue());
        }
        if (value != null && !String.valueOf(value).isBlank()) {
            try {
                return Math.max(0, Long.parseLong(String.valueOf(value)));
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
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

    private String format(double value) {
        return String.format(Locale.ROOT, "%.3f", Math.max(0.0, value));
    }

    private double round3(double value) {
        return Math.round(value * 1000.0d) / 1000.0d;
    }

    private String timeLabel(double start, double end) {
        return secondsLabel(start) + " - " + secondsLabel(end);
    }

    private String secondsLabel(double value) {
        int seconds = Math.max(0, (int) Math.round(value));
        return "%02d:%02d".formatted(seconds / 60, seconds % 60);
    }

    private String compact(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value == null ? "" : value;
        }
        return value.substring(0, Math.max(0, maxLength)) + "...";
    }

    private String stringValue(Object value, String fallback) {
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }

    private String escape(String value) {
        if (value == null) {
            return "";
        }
        try {
            return objectMapper.writeValueAsString(value).replaceAll("^\"|\"$", "");
        } catch (JsonProcessingException ex) {
            return value;
        }
    }

    private record PreparedVideoInput(byte[] bytes, String contentType, String source, int sampleSeconds, Map<String, Object> metadata) {
    }

    private record UploadedVideoFile(String fileUri, String mimeType, String name) {
    }

    public record ShortVideoUnderstandingResult(
            boolean mediaBacked,
            String provider,
            String model,
            Map<String, Object> videoDna,
            List<Map<String, Object>> transcript,
            Map<String, Object> transcriptCritic,
            Map<String, Object> videoTypeCritic,
            List<Map<String, Object>> trace,
            Map<String, Object> tokenMetadata,
            Map<String, Object> costMetadata,
            Map<String, Object> rawOutput
    ) {
    }
}
