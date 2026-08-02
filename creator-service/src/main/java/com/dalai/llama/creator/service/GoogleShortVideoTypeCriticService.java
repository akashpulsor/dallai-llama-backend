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

@Service
public class GoogleShortVideoTypeCriticService {

    private static final Logger log = LoggerFactory.getLogger(GoogleShortVideoTypeCriticService.class);
    private static final String PROMPT_TYPE = "SHORTS_VIDEO_TYPE_CRITIC";
    private static final int RESPONSE_MAX_IN_MEMORY_BYTES = 48 * 1024 * 1024;
    private static final int MAX_TOTAL_INLINE_MEDIA_BYTES = 22 * 1024 * 1024;
    private static final int MAX_VIDEO_SAMPLE_COUNT = 5;
    private static final int LONG_VIDEO_SAMPLE_SECONDS = 35;
    private static final int SHORT_VIDEO_MAX_SAMPLE_SECONDS = 180;
    private static final int MAX_TRANSCRIPT_PROMPT_CHARS = 22000;
    private static final Duration COMMAND_TIMEOUT = Duration.ofMinutes(12);
    private static final Set<String> PRIMARY_TYPES = Set.of("podcast", "interview", "tutorial", "sketch", "documentary", "reaction", "gaming", "presentation", "review", "vlog", "unknown");
    private static final Set<String> STRUCTURE_TYPES = Set.of("qa", "dramatic", "instructional", "storytelling", "reveal", "reaction", "unknown");

    private final CreatorProperties properties;
    private final AssetStorageService assetStorageService;
    private final CreatorAiPricingService pricingService;
    private final GeminiUsageMetadataParser usageMetadataParser;
    private final GoogleGenAiClientFactory googleGenAiClientFactory;
    private final GeminiRateLimitGuard geminiRateLimitGuard;
    private final ObjectMapper objectMapper;

    public GoogleShortVideoTypeCriticService(
            CreatorProperties properties,
            AssetStorageService assetStorageService,
            CreatorAiPricingService pricingService,
            GeminiUsageMetadataParser usageMetadataParser,
            GoogleGenAiClientFactory googleGenAiClientFactory,
            GeminiRateLimitGuard geminiRateLimitGuard,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.assetStorageService = assetStorageService;
        this.pricingService = pricingService;
        this.usageMetadataParser = usageMetadataParser;
        this.googleGenAiClientFactory = googleGenAiClientFactory;
        this.geminiRateLimitGuard = geminiRateLimitGuard;
        this.objectMapper = objectMapper;
    }

    public VideoTypeCriticResult critique(
            CreatorShortVideo video,
            CreatorAsset sourceAsset,
            Map<String, Object> classifiedVideoDna,
            List<Map<String, Object>> transcript
    ) {
        return critique(video, sourceAsset, classifiedVideoDna, transcript, null);
    }

    public VideoTypeCriticResult critique(
            CreatorShortVideo video,
            CreatorAsset sourceAsset,
            Map<String, Object> classifiedVideoDna,
            List<Map<String, Object>> transcript,
            Path sourceVideoPath
    ) {
        String model = stringValue(properties.getAi().getGeminiModel(), "gemini-2.5-flash");
        Map<String, Object> safeVideoDna = sanitizeVideoDna(classifiedVideoDna, fallbackVideoDna(video));
        List<Map<String, Object>> safeTranscript = copyList(transcript);
        boolean reuseSource = reusableSourcePath(sourceVideoPath);
        if (!reuseSource && (sourceAsset == null || isBlank(sourceAsset.getBucket()) || isBlank(sourceAsset.getObjectKey()))) {
            return skipped(model, "WARN", "Source video storage location is missing; full-video type critic cannot verify classification.", safeVideoDna);
        }

        Path workspace = null;
        try {
            workspace = Files.createTempDirectory("creator-shorts-video-type-critic-");
            Path sourcePath = reuseSource
                    ? sourceVideoPath
                    : workspace.resolve("source" + extensionFor(sourceAsset.getContentType(), sourceAsset.getObjectKey()));
            Path sampleDir = Files.createDirectories(workspace.resolve("samples"));
            if (!reuseSource) {
                assetStorageService.downloadObjectToPath(sourceAsset.getBucket(), sourceAsset.getObjectKey(), sourcePath);
            }
            MediaInfo mediaInfo = probe(sourcePath);
            List<PreparedMediaPart> mediaParts = prepareMediaEvidence(sourcePath, sampleDir, mediaInfo);
            if (mediaParts.isEmpty()) {
                return skipped(model, "WARN", "Full-video type critic could not prepare media evidence from source video.", safeVideoDna);
            }

            String prompt = buildPrompt(video, sourceAsset, safeVideoDna, safeTranscript, mediaInfo, mediaParts);
            Map<String, Object> request = buildRequest(prompt, mediaParts);
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
            return normalizeResponse(model, prompt, response == null ? Map.of() : response, safeVideoDna, mediaInfo, mediaParts, safeTranscript.size());
        } catch (Exception ex) {
            log.warn("Full-video shorts type critic failed videoId={} message={}", video == null ? null : video.getId(), ex.getMessage());
            return skipped(model, "WARN", "Full-video type critic failed: " + safeMessage(ex), safeVideoDna);
        } finally {
            deleteQuietly(workspace);
        }
    }

    private boolean reusableSourcePath(Path sourceVideoPath) {
        return sourceVideoPath != null && Files.isRegularFile(sourceVideoPath);
    }

    private List<PreparedMediaPart> prepareMediaEvidence(Path sourcePath, Path sampleDir, MediaInfo mediaInfo) {
        List<PreparedMediaPart> parts = new ArrayList<>();
        long totalBytes = 0;
        List<SampleWindow> windows = sampleWindows(mediaInfo.durationSeconds());
        for (SampleWindow window : windows) {
            Path videoSample = sampleDir.resolve("sample-%02d.mp4".formatted(window.index()));
            try {
                extractVideoSample(sourcePath, videoSample, window.startSeconds(), window.durationSeconds(), 640, 34, 15, 48);
                byte[] bytes = Files.readAllBytes(videoSample);
                if (bytes.length > 0 && totalBytes + bytes.length <= MAX_TOTAL_INLINE_MEDIA_BYTES) {
                    Map<String, Object> metadata = sampleMetadata(window, "video/mp4", bytes.length, "ffmpeg_temporal_video_sample");
                    parts.add(new PreparedMediaPart("video/mp4", bytes, metadata));
                    totalBytes += bytes.length;
                    continue;
                }
            } catch (Exception ex) {
                log.info("Video type critic video sample failed window={} message={}", window.index(), ex.getMessage());
            }

            Path frameSample = sampleDir.resolve("frame-%02d.jpg".formatted(window.index()));
            try {
                extractFrameSample(sourcePath, frameSample, window.midpointSeconds());
                byte[] bytes = Files.readAllBytes(frameSample);
                if (bytes.length > 0 && totalBytes + bytes.length <= MAX_TOTAL_INLINE_MEDIA_BYTES) {
                    Map<String, Object> metadata = sampleMetadata(window, "image/jpeg", bytes.length, "ffmpeg_temporal_frame_fallback");
                    parts.add(new PreparedMediaPart("image/jpeg", bytes, metadata));
                    totalBytes += bytes.length;
                }
            } catch (Exception ex) {
                log.info("Video type critic frame fallback failed window={} message={}", window.index(), ex.getMessage());
            }
        }
        return parts;
    }

    private List<SampleWindow> sampleWindows(double durationSeconds) {
        double duration = durationSeconds > 0 ? durationSeconds : 60.0;
        List<SampleWindow> windows = new ArrayList<>();
        if (duration <= SHORT_VIDEO_MAX_SAMPLE_SECONDS) {
            windows.add(new SampleWindow(1, 0.0, Math.max(1.0, Math.min(duration, SHORT_VIDEO_MAX_SAMPLE_SECONDS)), "compact_full_video"));
            return windows;
        }

        double sampleSeconds = Math.min(LONG_VIDEO_SAMPLE_SECONDS, Math.max(12.0, duration / 10.0));
        double maxStart = Math.max(0.0, duration - sampleSeconds);
        TreeSet<Double> starts = new TreeSet<>();
        starts.add(0.0);
        starts.add(clamp(duration * 0.25 - sampleSeconds / 2.0, 0.0, maxStart));
        starts.add(clamp(duration * 0.50 - sampleSeconds / 2.0, 0.0, maxStart));
        starts.add(clamp(duration * 0.75 - sampleSeconds / 2.0, 0.0, maxStart));
        starts.add(maxStart);

        int index = 1;
        for (Double start : starts) {
            if (index > MAX_VIDEO_SAMPLE_COUNT) {
                break;
            }
            windows.add(new SampleWindow(index, round3(start), round3(Math.min(sampleSeconds, duration - start)), windowLabel(index, starts.size())));
            index++;
        }
        return windows;
    }

    private String windowLabel(int index, int count) {
        if (index == 1) return "opening";
        if (index == count) return "ending";
        return "middle_" + index;
    }

    private void extractVideoSample(Path sourcePath, Path outputPath, double start, double duration, int width, int crf, int fps, int audioKbps) {
        run(List.of(
                "ffmpeg", "-hide_banner", "-y",
                "-ss", format(start),
                "-i", sourcePath.toString(),
                "-t", format(duration),
                "-map", "0:v:0",
                "-map", "0:a?",
                "-vf", evenDimensionVideoFilter(width, fps),
                "-c:v", "libx264",
                "-preset", "veryfast",
                "-crf", String.valueOf(crf),
                "-pix_fmt", "yuv420p",
                "-c:a", "aac",
                "-b:a", audioKbps + "k",
                "-ac", "1",
                "-ar", "16000",
                "-movflags", "+faststart",
                outputPath.toString()
        ), "ffmpeg video type sample extraction");
    }

    private String evenDimensionVideoFilter(int width, int fps) {
        int safeWidth = Math.max(2, width);
        int safeFps = Math.max(1, fps);
        return "fps=" + safeFps
                + ",scale=" + safeWidth + ":-2:force_original_aspect_ratio=decrease"
                + ",pad=ceil(iw/2)*2:ceil(ih/2)*2"
                + ",setsar=1";
    }

    private void extractFrameSample(Path sourcePath, Path outputPath, double timestamp) {
        run(List.of(
                "ffmpeg", "-hide_banner", "-y",
                "-ss", format(timestamp),
                "-i", sourcePath.toString(),
                "-frames:v", "1",
                "-vf", "scale=640:-2:force_original_aspect_ratio=decrease",
                "-q:v", "4",
                outputPath.toString()
        ), "ffmpeg video type frame extraction");
    }

    private Map<String, Object> sampleMetadata(SampleWindow window, String mimeType, int bytes, String source) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("index", window.index());
        metadata.put("label", window.label());
        metadata.put("startSeconds", window.startSeconds());
        metadata.put("durationSeconds", window.durationSeconds());
        metadata.put("endSeconds", round3(window.startSeconds() + window.durationSeconds()));
        metadata.put("midpointSeconds", window.midpointSeconds());
        metadata.put("mimeType", mimeType);
        metadata.put("bytes", bytes);
        metadata.put("source", source);
        return metadata;
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
            ), "ffprobe video type critic media info");
            List<String> lines = output.lines().map(String::trim).filter(line -> !line.isBlank()).toList();
            int width = lines.size() > 0 ? intValue(lines.get(0), 0) : 0;
            int height = lines.size() > 1 ? intValue(lines.get(1), 0) : 0;
            double duration = lines.size() > 2 ? doubleValue(lines.get(2), 0.0) : 0.0;
            return new MediaInfo(width, height, duration);
        } catch (RuntimeException ex) {
            log.info("Video type critic media probe failed: {}", ex.getMessage());
            return new MediaInfo(0, 0, 0.0);
        }
    }
    private Map<String, Object> buildRequest(String prompt, List<PreparedMediaPart> mediaParts) {
        List<Map<String, Object>> parts = new ArrayList<>();
        parts.add(Map.of("text", prompt));
        for (PreparedMediaPart mediaPart : mediaParts) {
            parts.add(Map.of("inline_data", Map.of(
                    "mime_type", mediaPart.mimeType(),
                    "data", Base64.getEncoder().encodeToString(mediaPart.bytes())
            )));
        }

        Map<String, Object> generationConfig = new LinkedHashMap<>();
        generationConfig.put("responseMimeType", "application/json");
        if (properties.getAi().getMaxOutputTokens() != null && properties.getAi().getMaxOutputTokens() > 0) {
            generationConfig.put("maxOutputTokens", properties.getAi().getMaxOutputTokens());
        }

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("systemInstruction", Map.of("parts", List.of(Map.of("text", "Return only one valid JSON object. Do not wrap it in markdown."))));
        request.put("contents", List.of(Map.of("role", "user", "parts", parts)));
        request.put("generationConfig", generationConfig);
        return request;
    }

    private String buildPrompt(
            CreatorShortVideo video,
            CreatorAsset sourceAsset,
            Map<String, Object> classifiedVideoDna,
            List<Map<String, Object>> transcript,
            MediaInfo mediaInfo,
            List<PreparedMediaPart> mediaParts
    ) {
        return """
                You are the independent VIDEO_TYPE_CRITIC for a Generate Shorts backend pipeline.

                The upstream VIDEO_TYPE_CLASSIFICATION came from an early bounded sample. Your job is to verify or correct it using evidence from the full source: representative temporal media samples from across the video plus the full transcript digest. Do not rubber-stamp the upstream label.

                Source metadata:
                {
                  "shortVideoId": "%s",
                  "title": "%s",
                  "originalFileName": "%s",
                  "platform": "%s",
                  "targetDurationSeconds": %s,
                  "requestedShorts": %s,
                  "assetId": "%s",
                  "durationSeconds": %s,
                  "width": %s,
                  "height": %s
                }

                Upstream classified videoDna:
                %s

                Full transcript digest:
                %s

                Media evidence windows attached after this text:
                %s

                Return JSON only with this exact top-level shape:
                {
                  "status": "PASS|WARN|FAIL",
                  "classificationDecision": "confirm|correct|uncertain",
                  "confidence": 0.0,
                  "confirmedVideoDna": {
                    "primaryType": "podcast|interview|tutorial|sketch|documentary|reaction|gaming|presentation|review|vlog|unknown",
                    "structureType": "qa|dramatic|instructional|storytelling|reveal|reaction|unknown",
                    "confidence": 0.0,
                    "analysisSource": "full_video_type_critic",
                    "reason": "why this label is correct for the whole video"
                  },
                  "primaryTypeEvidence": ["concrete evidence from transcript/media"],
                  "structureEvidence": ["evidence for qa/tutorial/story/reaction/etc"],
                  "contradictions": ["evidence that disagrees with upstream classification"],
                  "alternatives": [{"primaryType":"interview","structureType":"qa","confidence":0.0,"reason":"..."}],
                  "sampleAudits": [
                    {"index":1,"label":"opening","observedFormat":"...","classificationHint":"...","confidence":0.0,"evidence":"..."}
                  ],
                  "issues": [],
                  "summary": "one sentence staff-level verdict"
                }

                Decision policy:
                - confirm: upstream classification is supported by the full-video evidence.
                - correct: upstream classification is materially wrong; provide corrected confirmedVideoDna.
                - uncertain: evidence is mixed or weak; keep the safest label and explain why.
                - PASS means the label is safe for downstream graph/compression decisions.
                - WARN means use with caution or corrected with moderate confidence.
                - FAIL means classification is unsafe without human review, but still return your best confirmedVideoDna.
                """.formatted(
                video == null || video.getId() == null ? "" : video.getId(),
                video == null ? "" : escape(video.getTitle()),
                video == null ? "" : escape(video.getOriginalFileName()),
                video == null ? "" : escape(video.getPlatform()),
                video == null || video.getTargetDurationSeconds() == null ? 60 : video.getTargetDurationSeconds(),
                video == null || video.getRequestedShorts() == null ? 20 : video.getRequestedShorts(),
                sourceAsset == null || sourceAsset.getId() == null ? "" : sourceAsset.getId(),
                round3(mediaInfo.durationSeconds()),
                mediaInfo.width(),
                mediaInfo.height(),
                toJson(classifiedVideoDna),
                compact(toJson(transcriptDigest(transcript)), MAX_TRANSCRIPT_PROMPT_CHARS),
                toJson(mediaParts.stream().map(PreparedMediaPart::metadata).toList())
        );
    }

    private VideoTypeCriticResult normalizeResponse(
            String model,
            String prompt,
            Map<String, Object> response,
            Map<String, Object> classifiedVideoDna,
            MediaInfo mediaInfo,
            List<PreparedMediaPart> mediaParts,
            int transcriptNodeCount
    ) {
        String rawText = outputText(response);
        Map<String, Object> output = parseJsonObject(rawText);
        Map<String, Object> usage = usageMetadataParser.parse(response.get("usageMetadata"));
        long fallbackInputTokens = pricingService.estimateTextTokens(prompt);
        long providerInputTokens = longValue(usage.get("inputTokens"));
        long inputTokens = Math.max(providerInputTokens, fallbackInputTokens);
        long outputTokens = longValue(usage.get("outputTokens"));
        String usageSource = providerInputTokens > 0 || outputTokens > 0 ? "PROVIDER" : "ESTIMATED_TEXT_ONLY";

        Map<String, Object> tokenMetadata = new LinkedHashMap<>(usage);
        tokenMetadata.put("inputTokens", inputTokens);
        tokenMetadata.put("outputTokens", outputTokens);
        tokenMetadata.put("totalTokens", Math.max(longValue(usage.get("totalTokens")), inputTokens + outputTokens));
        tokenMetadata.put("source", usageSource);

        Map<String, Object> costMetadata = pricingService.estimateTextCall("gemini", model, PROMPT_TYPE, inputTokens, outputTokens, usageSource);
        costMetadata.put("provider", "gemini");
        costMetadata.put("model", model);
        costMetadata.put("operation", PROMPT_TYPE);
        costMetadata.put("googleGenaiBackend", googleGenAiClientFactory.backend());

        String status = normalizeStatus(output.get("status"));
        String decision = normalizeDecision(output.get("classificationDecision"));
        double confidence = clamp(doubleValue(output.get("confidence"), 0.0), 0.0, 1.0);
        Map<String, Object> confirmedVideoDna = sanitizeVideoDna(mapValue(output.get("confirmedVideoDna")), classifiedVideoDna);
        confirmedVideoDna.put("analysisSource", "full_video_type_critic");
        confirmedVideoDna.put("criticDecision", decision);
        confirmedVideoDna.put("criticStatus", status);
        confirmedVideoDna.put("criticConfidence", confidence);
        if (!"confirm".equals(decision) || "unknown".equals(stringValue(classifiedVideoDna.get("primaryType"), "unknown"))) {
            confirmedVideoDna.put("reason", defaultString(confirmedVideoDna.get("reason"), defaultString(output.get("summary"), "Full-video type critic supplied the safest available classification.")));
        }

        Map<String, Object> critic = new LinkedHashMap<>();
        critic.put("status", status);
        critic.put("classificationDecision", decision);
        critic.put("confidence", confidence);
        critic.put("summary", defaultString(output.get("summary"), "Full-video type critic completed."));
        critic.put("primaryTypeEvidence", listValue(output.get("primaryTypeEvidence")));
        critic.put("structureEvidence", listValue(output.get("structureEvidence")));
        critic.put("contradictions", listValue(output.get("contradictions")));
        critic.put("alternatives", listValue(output.get("alternatives")));
        critic.put("sampleAudits", listValue(output.get("sampleAudits")));
        critic.put("issues", listValue(output.get("issues")));
        critic.put("upstreamVideoDna", classifiedVideoDna);
        critic.put("confirmedVideoDna", confirmedVideoDna);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "gemini_full_video_type_critic");
        metadata.put("status", status);
        metadata.put("classificationDecision", decision);
        metadata.put("generatedAt", OffsetDateTime.now().toString());
        metadata.put("model", model);
        metadata.put("durationSeconds", round3(mediaInfo.durationSeconds()));
        metadata.put("width", mediaInfo.width());
        metadata.put("height", mediaInfo.height());
        metadata.put("transcriptNodeCount", transcriptNodeCount);
        metadata.put("sampleCount", mediaParts.size());
        metadata.put("samples", mediaParts.stream().map(PreparedMediaPart::metadata).toList());
        metadata.put("critic", critic);
        metadata.put("rawTextPreview", compact(rawText, 4000));

        Map<String, Object> traceMetadata = new LinkedHashMap<>();
        traceMetadata.put("source", "gemini_full_video_type_critic");
        traceMetadata.put("classificationDecision", decision);
        traceMetadata.put("upstreamPrimaryType", classifiedVideoDna.getOrDefault("primaryType", "unknown"));
        traceMetadata.put("confirmedPrimaryType", confirmedVideoDna.getOrDefault("primaryType", "unknown"));
        traceMetadata.put("upstreamStructureType", classifiedVideoDna.getOrDefault("structureType", "unknown"));
        traceMetadata.put("confirmedStructureType", confirmedVideoDna.getOrDefault("structureType", "unknown"));
        traceMetadata.put("sampleCount", mediaParts.size());
        traceMetadata.put("issues", critic.get("issues"));

        List<Map<String, Object>> trace = List.of(traceRow("VIDEO_TYPE_CRITIC", status, stringValue(critic.get("summary"), "Full-video type critic completed."), confidence, traceMetadata));
        return new VideoTypeCriticResult(true, "gemini", model, confirmedVideoDna, critic, trace, mediaParts.stream().map(PreparedMediaPart::metadata).toList(), tokenMetadata, costMetadata, metadata);
    }
    private Map<String, Object> transcriptDigest(List<Map<String, Object>> transcript) {
        List<Map<String, Object>> safeTranscript = copyList(transcript);
        Map<String, Object> digest = new LinkedHashMap<>();
        digest.put("nodeCount", safeTranscript.size());
        digest.put("durationSeconds", round3(transcriptDuration(safeTranscript)));
        digest.put("sampledNodes", sampledTranscriptNodes(safeTranscript));
        digest.put("speakerCount", safeTranscript.stream().map(node -> stringValue(node.get("speaker"), "Speaker 1")).distinct().count());
        return digest;
    }

    private List<Map<String, Object>> sampledTranscriptNodes(List<Map<String, Object>> transcript) {
        List<Map<String, Object>> sampled = new ArrayList<>();
        if (transcript == null || transcript.isEmpty()) {
            return sampled;
        }
        TreeSet<Integer> indexes = new TreeSet<>();
        int size = transcript.size();
        for (int index = 0; index < Math.min(4, size); index++) indexes.add(index);
        for (int index = Math.max(0, size - 4); index < size; index++) indexes.add(index);
        if (size > 8) {
            indexes.add(Math.max(0, size / 4));
            indexes.add(Math.max(0, size / 2));
            indexes.add(Math.max(0, (size * 3) / 4));
        }
        for (Integer index : indexes) {
            Map<String, Object> source = transcript.get(index);
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", source.getOrDefault("id", "n-" + (index + 1)));
            node.put("start", source.getOrDefault("start", 0));
            node.put("end", source.getOrDefault("end", 0));
            node.put("speaker", source.getOrDefault("speaker", "Speaker 1"));
            node.put("sceneId", source.getOrDefault("sceneId", ""));
            node.put("transcript", compact(stringValue(source.get("transcript"), ""), 900));
            sampled.add(node);
        }
        return sampled;
    }

    private double transcriptDuration(List<Map<String, Object>> transcript) {
        double duration = 0.0;
        for (Map<String, Object> node : transcript == null ? List.<Map<String, Object>>of() : transcript) {
            duration = Math.max(duration, doubleValue(node.get("end"), 0.0));
        }
        return duration;
    }

    private VideoTypeCriticResult skipped(String model, String status, String reason, Map<String, Object> videoDna) {
        Map<String, Object> safeVideoDna = sanitizeVideoDna(videoDna, fallbackVideoDna(null));
        Map<String, Object> critic = new LinkedHashMap<>();
        critic.put("status", status);
        critic.put("classificationDecision", "uncertain");
        critic.put("confidence", 0.2);
        critic.put("summary", reason);
        critic.put("issues", List.of(reason));
        critic.put("confirmedVideoDna", safeVideoDna);
        critic.put("upstreamVideoDna", safeVideoDna);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "gemini_full_video_type_critic");
        metadata.put("status", status);
        metadata.put("reason", reason);
        metadata.put("generatedAt", OffsetDateTime.now().toString());
        metadata.put("critic", critic);

        Map<String, Object> tokenMetadata = new LinkedHashMap<>();
        tokenMetadata.put("inputTokens", 0);
        tokenMetadata.put("outputTokens", 0);
        tokenMetadata.put("totalTokens", 0);
        tokenMetadata.put("source", "NONE");
        Map<String, Object> costMetadata = pricingService.estimateTextCall("gemini", model, PROMPT_TYPE, 0, 0, "NONE");
        costMetadata.put("provider", "gemini");
        costMetadata.put("model", model);
        costMetadata.put("operation", PROMPT_TYPE);

        List<Map<String, Object>> trace = List.of(traceRow("VIDEO_TYPE_CRITIC", status, reason, 0.2, Map.of("source", "gemini_full_video_type_critic")));
        return new VideoTypeCriticResult(false, "gemini", model, safeVideoDna, critic, trace, List.of(), tokenMetadata, costMetadata, metadata);
    }

    private Map<String, Object> sanitizeVideoDna(Map<String, Object> candidate, Map<String, Object> fallback) {
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Object> safeFallback = fallback == null ? new LinkedHashMap<>() : fallback;
        String primaryType = normalizePrimaryType(candidate == null ? null : candidate.get("primaryType"));
        if ("unknown".equals(primaryType)) primaryType = normalizePrimaryType(safeFallback.get("primaryType"));
        String structureType = normalizeStructureType(candidate == null ? null : candidate.get("structureType"));
        if ("unknown".equals(structureType)) structureType = normalizeStructureType(safeFallback.get("structureType"));
        result.put("primaryType", primaryType);
        result.put("structureType", structureType);
        result.put("confidence", clamp(doubleValue(candidate == null ? null : candidate.get("confidence"), doubleValue(safeFallback.get("confidence"), 0.0)), 0.0, 1.0));
        result.put("analysisSource", defaultString(candidate == null ? null : candidate.get("analysisSource"), defaultString(safeFallback.get("analysisSource"), "full_video_type_critic")));
        result.put("reason", defaultString(candidate == null ? null : candidate.get("reason"), defaultString(safeFallback.get("reason"), "Video type selected from available evidence.")));
        return result;
    }

    private Map<String, Object> fallbackVideoDna(CreatorShortVideo video) {
        Map<String, Object> dna = new LinkedHashMap<>();
        dna.put("primaryType", "unknown");
        dna.put("structureType", "unknown");
        dna.put("confidence", 0.0);
        dna.put("analysisSource", "full_video_type_critic_fallback");
        dna.put("reason", video == null ? "No upstream classification was available." : "No upstream classification was available for " + defaultString(video.getTitle(), "uploaded video") + ".");
        return dna;
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
                        Object partText = mapValue(partItem).get("text");
                        if (partText != null && !String.valueOf(partText).isBlank()) text.append(partText);
                    }
                }
            }
            return text.toString();
        }
        return "";
    }

    private Map<String, Object> parseJsonObject(String outputText) {
        if (outputText == null || outputText.isBlank()) return new LinkedHashMap<>();
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
        if (start < 0) return "";
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
            if (inString) continue;
            if (character == '{') depth++;
            if (character == '}') {
                depth--;
                if (depth == 0) return text.substring(start, index + 1);
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

    private Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            map.forEach((key, item) -> {
                if (key != null) normalized.put(String.valueOf(key), item);
            });
            return normalized;
        }
        return new LinkedHashMap<>();
    }

    private List<Object> listValue(Object value) {
        if (value instanceof List<?> list) return new ArrayList<>(list);
        if (value == null || String.valueOf(value).isBlank()) return new ArrayList<>();
        return new ArrayList<>(List.of(String.valueOf(value)));
    }

    private List<Map<String, Object>> copyList(List<Map<String, Object>> value) {
        List<Map<String, Object>> copy = new ArrayList<>();
        if (value == null) return copy;
        for (Map<String, Object> item : value) {
            copy.add(item == null ? new LinkedHashMap<>() : new LinkedHashMap<>(item));
        }
        return copy;
    }

    private String normalizePrimaryType(Object value) {
        String type = stringValue(value, "unknown").trim().toLowerCase(Locale.ROOT).replace('-', '_');
        if ("youtube".equals(type) || "talking_head".equals(type) || "podcast_clip".equals(type)) type = "podcast";
        if ("how_to".equals(type) || "education".equals(type)) type = "tutorial";
        if (PRIMARY_TYPES.contains(type)) return type;
        return "unknown";
    }

    private String normalizeStructureType(Object value) {
        String type = stringValue(value, "unknown").trim().toLowerCase(Locale.ROOT).replace('-', '_');
        if ("question_answer".equals(type) || "interview".equals(type)) type = "qa";
        if ("educational".equals(type) || "how_to".equals(type)) type = "instructional";
        if (STRUCTURE_TYPES.contains(type)) return type;
        return "unknown";
    }

    private String normalizeStatus(Object value) {
        String status = stringValue(value, "WARN").trim().toUpperCase(Locale.ROOT);
        return switch (status) {
            case "PASS", "PASSED", "COMPLETED", "OK" -> "PASS";
            case "FAIL", "FAILED", "ERROR" -> "FAIL";
            default -> "WARN";
        };
    }

    private String normalizeDecision(Object value) {
        String decision = stringValue(value, "uncertain").trim().toLowerCase(Locale.ROOT);
        return switch (decision) {
            case "confirm", "confirmed", "pass" -> "confirm";
            case "correct", "corrected", "reclassify", "override" -> "correct";
            default -> "uncertain";
        };
    }

    private String extensionFor(String contentType, String objectKey) {
        String normalized = stringValue(contentType, "").toLowerCase(Locale.ROOT);
        if (normalized.contains("quicktime")) return ".mov";
        if (normalized.contains("webm")) return ".webm";
        if (normalized.contains("x-matroska")) return ".mkv";
        String key = stringValue(objectKey, "").toLowerCase(Locale.ROOT);
        int dot = key.lastIndexOf('.');
        if (dot >= 0 && dot < key.length() - 1) return key.substring(dot);
        return ".mp4";
    }

    private String run(List<String> command, String label) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            boolean finished = process.waitFor(COMMAND_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            String output = new String(process.getInputStream().readAllBytes());
            if (!finished) {
                process.destroyForcibly();
                throw new IllegalStateException(label + " timed out.");
            }
            if (process.exitValue() != 0) throw new IllegalStateException(label + " failed: " + tail(output, 3000));
            return output;
        } catch (IOException ex) {
            throw new IllegalStateException("ffmpeg/ffprobe is not available for " + label + ".", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(label + " was interrupted.", ex);
        }
    }

    private void deleteQuietly(Path path) {
        if (path == null) return;
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

    private long longValue(Object value) {
        if (value instanceof Number number) return Math.max(0, number.longValue());
        if (value != null && !String.valueOf(value).isBlank()) {
            try {
                return Math.max(0, Long.parseLong(String.valueOf(value)));
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private int intValue(Object value, int fallback) {
        if (value instanceof Number number) return number.intValue();
        if (value != null && !String.valueOf(value).isBlank()) {
            try {
                return Integer.parseInt(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private double doubleValue(Object value, double fallback) {
        if (value instanceof Number number) return number.doubleValue();
        if (value != null && !String.valueOf(value).isBlank()) {
            try {
                return Double.parseDouble(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private double round3(double value) {
        return Math.round(value * 1000.0d) / 1000.0d;
    }

    private String format(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private String tail(String value, int maxLength) {
        String safe = value == null ? "" : value;
        return safe.length() <= maxLength ? safe : safe.substring(safe.length() - maxLength);
    }

    private String compact(String value, int maxLength) {
        String safe = value == null ? "" : value;
        if (safe.length() <= maxLength) return safe;
        return safe.substring(0, Math.max(0, maxLength)) + "...";
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String stringValue(Object value, String fallback) {
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }

    private String defaultString(Object value, String fallback) {
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }

    private String safeMessage(Exception ex) {
        return ex == null || ex.getMessage() == null ? "unknown error" : ex.getMessage();
    }

    private String escape(String value) {
        if (value == null) return "";
        try {
            return objectMapper.writeValueAsString(value).replaceAll("^\"|\"$", "");
        } catch (JsonProcessingException ex) {
            return value;
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            return String.valueOf(value);
        }
    }

    private record MediaInfo(int width, int height, double durationSeconds) {
    }

    private record SampleWindow(int index, double startSeconds, double durationSeconds, String label) {
        double midpointSeconds() {
            return Math.round((startSeconds + Math.max(0.0, durationSeconds) / 2.0) * 1000.0d) / 1000.0d;
        }
    }

    private record PreparedMediaPart(String mimeType, byte[] bytes, Map<String, Object> metadata) {
    }

    public record VideoTypeCriticResult(
            boolean mediaBacked,
            String provider,
            String model,
            Map<String, Object> videoDna,
            Map<String, Object> videoTypeCritic,
            List<Map<String, Object>> trace,
            List<Map<String, Object>> sampleMetadata,
            Map<String, Object> tokenMetadata,
            Map<String, Object> costMetadata,
            Map<String, Object> metadata
    ) {
    }
}
