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
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

@Service
public class GoogleShortSceneCriticService {

    private static final Logger log = LoggerFactory.getLogger(GoogleShortSceneCriticService.class);
    private static final String PROMPT_TYPE = "SHORTS_SCENE_CRITIC";
    private static final int RESPONSE_MAX_IN_MEMORY_BYTES = 48 * 1024 * 1024;
    private static final int MAX_TOTAL_INLINE_IMAGE_BYTES = 18 * 1024 * 1024;
    private static final int MAX_EVIDENCE_IMAGES = 34;
    private static final int MAX_REPRESENTATIVE_SCENES = 14;
    private static final int MAX_TRANSITION_BOUNDARIES = 10;
    private static final int FAST_MAX_TOTAL_INLINE_IMAGE_BYTES = 6 * 1024 * 1024;
    private static final int FAST_MAX_EVIDENCE_IMAGES = 10;
    private static final int FAST_MAX_REPRESENTATIVE_SCENES = 6;
    private static final int FAST_MAX_TRANSITION_BOUNDARIES = 2;
    private static final int FULL_SCENE_CRITIC_LIMIT_SECONDS = 10 * 60;
    private static final int STANDARD_FRAME_WIDTH = 720;
    private static final int FAST_FRAME_WIDTH = 480;
    private static final int STANDARD_JPEG_QUALITY = 4;
    private static final int FAST_JPEG_QUALITY = 7;
    private static final int MAX_TRANSCRIPT_PROMPT_CHARS = 18000;
    private static final Duration COMMAND_TIMEOUT = Duration.ofMinutes(12);

    private final CreatorProperties properties;
    private final AssetStorageService assetStorageService;
    private final CreatorAiPricingService pricingService;
    private final GeminiUsageMetadataParser usageMetadataParser;
    private final GoogleGenAiClientFactory googleGenAiClientFactory;
    private final GeminiRateLimitGuard geminiRateLimitGuard;
    private final ShortSourceStageCacheService sourceStageCacheService;
    private final ObjectMapper objectMapper;

    public GoogleShortSceneCriticService(
            CreatorProperties properties,
            AssetStorageService assetStorageService,
            CreatorAiPricingService pricingService,
            GeminiUsageMetadataParser usageMetadataParser,
            GoogleGenAiClientFactory googleGenAiClientFactory,
            GeminiRateLimitGuard geminiRateLimitGuard,
            ShortSourceStageCacheService sourceStageCacheService,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.assetStorageService = assetStorageService;
        this.pricingService = pricingService;
        this.usageMetadataParser = usageMetadataParser;
        this.googleGenAiClientFactory = googleGenAiClientFactory;
        this.geminiRateLimitGuard = geminiRateLimitGuard;
        this.sourceStageCacheService = sourceStageCacheService;
        this.objectMapper = objectMapper;
    }

    public SceneCriticResult critique(
            CreatorShortVideo video,
            CreatorAsset sourceAsset,
            Map<String, Object> videoDna,
            List<Map<String, Object>> scenes,
            List<Map<String, Object>> frames,
            List<Map<String, Object>> transcript
    ) {
        return critique(video, sourceAsset, videoDna, scenes, frames, transcript, null);
    }

    public SceneCriticResult critique(
            CreatorShortVideo video,
            CreatorAsset sourceAsset,
            Map<String, Object> videoDna,
            List<Map<String, Object>> scenes,
            List<Map<String, Object>> frames,
            List<Map<String, Object>> transcript,
            Path sourceVideoPath
    ) {
        String model = stringValue(properties.getAi().getGeminiModel(), "gemini-2.5-flash");
        List<Map<String, Object>> safeScenes = copyList(scenes);
        List<Map<String, Object>> safeFrames = copyList(frames);
        List<Map<String, Object>> safeTranscript = copyList(transcript);
        Map<String, Object> safeVideoDna = videoDna == null ? new LinkedHashMap<>() : new LinkedHashMap<>(videoDna);
        if (safeScenes.isEmpty()) {
            return skipped(model, "WARN", "Scene analysis did not produce scenes for deep vision critique.", safeScenes, safeFrames);
        }
        boolean reuseSource = reusableSourcePath(sourceVideoPath);
        if (!reuseSource && (sourceAsset == null || isBlank(sourceAsset.getBucket()) || isBlank(sourceAsset.getObjectKey()))) {
            return skipped(model, "WARN", "Source video storage location is missing; deep scene critic cannot extract visual evidence.", safeScenes, safeFrames);
        }
        String sceneFingerprint = payloadFingerprint(List.of(sceneDigest(safeScenes), transcriptSceneDigest(safeTranscript), frameMetadataForPrompt(safeFrames)));
        SceneCriticResult cached = completedSceneCriticFromSourceCache(video, sourceAsset, model, sceneFingerprint);
        if (cached != null) {
            return cached;
        }

        Path workspace = null;
        long startedAt = System.nanoTime();
        long sourceDownloadMs = 0L;
        long probeMs;
        long evidenceMs;
        long geminiMs;
        try {
            workspace = Files.createTempDirectory("creator-shorts-scene-critic-");
            Path sourcePath = reuseSource
                    ? sourceVideoPath
                    : workspace.resolve("source" + extensionFor(sourceAsset.getContentType(), sourceAsset.getObjectKey()));
            Path evidenceDir = Files.createDirectories(workspace.resolve("evidence"));
            if (!reuseSource) {
                long downloadStartedAt = System.nanoTime();
                assetStorageService.downloadObjectToPath(sourceAsset.getBucket(), sourceAsset.getObjectKey(), sourcePath);
                sourceDownloadMs = elapsedMs(downloadStartedAt);
            }
            long probeStartedAt = System.nanoTime();
            MediaInfo mediaInfo = probe(sourcePath);
            probeMs = elapsedMs(probeStartedAt);
            boolean fastAuditMode = shouldUseFastAuditMode(mediaInfo, safeScenes, safeFrames);
            long evidenceStartedAt = System.nanoTime();
            List<PreparedImage> evidence = prepareVisionEvidence(sourcePath, evidenceDir, safeScenes, safeFrames, mediaInfo, fastAuditMode);
            evidenceMs = elapsedMs(evidenceStartedAt);
            if (evidence.isEmpty()) {
                return skipped(model, "WARN", "Deep scene critic could not prepare visual evidence from source video.", safeScenes, safeFrames);
            }
            long evidenceBytes = totalEvidenceBytes(evidence);
            log.info("Deep scene critic evidence prepared videoId={} fastAuditMode={} durationSeconds={} sceneCount={} frameCount={} evidenceImageCount={} evidenceBytes={} sourceDownloadMs={} probeMs={} evidenceMs={}",
                    video == null ? null : video.getId(),
                    fastAuditMode,
                    round3(mediaInfo.durationSeconds()),
                    safeScenes.size(),
                    safeFrames.size(),
                    evidence.size(),
                    evidenceBytes,
                    sourceDownloadMs,
                    probeMs,
                    evidenceMs);

            String prompt = buildPrompt(video, sourceAsset, safeVideoDna, safeScenes, safeFrames, safeTranscript, mediaInfo, evidence, fastAuditMode);
            Map<String, Object> request = buildRequest(prompt, evidence);
            long geminiStartedAt = System.nanoTime();
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
            geminiMs = elapsedMs(geminiStartedAt);
            Map<String, Object> timingMs = new LinkedHashMap<>();
            timingMs.put("sourceDownloadMs", sourceDownloadMs);
            timingMs.put("probeMs", probeMs);
            timingMs.put("evidenceMs", evidenceMs);
            timingMs.put("geminiMs", geminiMs);
            timingMs.put("totalMs", elapsedMs(startedAt));
            log.info("Deep scene critic complete videoId={} fastAuditMode={} evidenceImageCount={} evidenceBytes={} geminiMs={} totalMs={}",
                    video == null ? null : video.getId(),
                    fastAuditMode,
                    evidence.size(),
                    evidenceBytes,
                    geminiMs,
                    timingMs.get("totalMs"));
            SceneCriticResult result = normalizeResponse(model, prompt, response == null ? Map.of() : response, safeScenes, safeFrames, safeTranscript, mediaInfo, evidence, fastAuditMode, evidenceBytes, timingMs);
            persistSceneCriticSourceCache(video, sourceAsset, model, sceneFingerprint, result);
            return result;
        } catch (Exception ex) {
            log.warn("Deep shorts scene critic failed videoId={} message={}", video == null ? null : video.getId(), ex.getMessage());
            return skipped(model, "WARN", "Deep scene critic failed: " + safeMessage(ex), safeScenes, safeFrames);
        } finally {
            deleteQuietly(workspace);
        }
    }

    private boolean reusableSourcePath(Path sourceVideoPath) {
        return sourceVideoPath != null && Files.isRegularFile(sourceVideoPath);
    }

    private boolean shouldUseFastAuditMode(MediaInfo mediaInfo, List<Map<String, Object>> scenes, List<Map<String, Object>> frames) {
        double durationSeconds = mediaInfo == null ? 0.0 : mediaInfo.durationSeconds();
        int sceneCount = scenes == null ? 0 : scenes.size();
        int frameCount = frames == null ? 0 : frames.size();
        return durationSeconds >= FULL_SCENE_CRITIC_LIMIT_SECONDS
                || sceneCount > 40
                || frameCount > 80;
    }

    private String auditMode(boolean fastAuditMode) {
        return fastAuditMode ? "FAST_REPRESENTATIVE" : "FULL_EVIDENCE";
    }

    private long totalEvidenceBytes(List<PreparedImage> evidence) {
        long total = 0L;
        for (PreparedImage image : evidence == null ? List.<PreparedImage>of() : evidence) {
            total += image.bytes() == null ? 0 : image.bytes().length;
        }
        return total;
    }

    private long elapsedMs(long startedAtNanos) {
        return TimeUnit.NANOSECONDS.toMillis(Math.max(0L, System.nanoTime() - startedAtNanos));
    }

    private List<PreparedImage> prepareVisionEvidence(
            Path sourcePath,
            Path evidenceDir,
            List<Map<String, Object>> scenes,
            List<Map<String, Object>> frames,
            MediaInfo mediaInfo,
            boolean fastAuditMode
    ) {
        List<PreparedImage> images = new ArrayList<>();
        long totalBytes = 0;
        int maxEvidenceImages = fastAuditMode ? FAST_MAX_EVIDENCE_IMAGES : MAX_EVIDENCE_IMAGES;
        int maxInlineBytes = fastAuditMode ? FAST_MAX_TOTAL_INLINE_IMAGE_BYTES : MAX_TOTAL_INLINE_IMAGE_BYTES;
        int maxRepresentativeScenes = fastAuditMode ? FAST_MAX_REPRESENTATIVE_SCENES : MAX_REPRESENTATIVE_SCENES;
        int maxTransitionBoundaries = fastAuditMode ? FAST_MAX_TRANSITION_BOUNDARIES : MAX_TRANSITION_BOUNDARIES;
        int frameWidth = fastAuditMode ? FAST_FRAME_WIDTH : STANDARD_FRAME_WIDTH;
        int jpegQuality = fastAuditMode ? FAST_JPEG_QUALITY : STANDARD_JPEG_QUALITY;
        String auditMode = auditMode(fastAuditMode);

        for (Integer sceneIndex : sampledSceneIndexes(scenes, maxRepresentativeScenes)) {
            if (images.size() >= maxEvidenceImages) {
                break;
            }
            Map<String, Object> scene = scenes.get(sceneIndex);
            double timestamp = representativeTimestamp(scene);
            Path output = evidenceDir.resolve("scene-%03d.jpg".formatted(sceneIndex + 1));
            try {
                extractFrame(sourcePath, output, timestamp, frameWidth, jpegQuality);
                byte[] bytes = Files.readAllBytes(output);
                if (bytes.length == 0 || totalBytes + bytes.length > maxInlineBytes) {
                    continue;
                }
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("kind", "scene_representative");
                metadata.put("imageIndex", images.size() + 1);
                metadata.put("sceneId", stringValue(scene.get("id"), "scene-" + (sceneIndex + 1)));
                metadata.put("sceneIndex", sceneIndex + 1);
                metadata.put("timestampSeconds", round3(timestamp));
                metadata.put("sceneStart", scene.getOrDefault("start", 0));
                metadata.put("sceneEnd", scene.getOrDefault("end", 0));
                metadata.put("source", "ffmpeg_scene_representative_frame");
                metadata.put("bytes", bytes.length);
                metadata.put("width", mediaInfo.width());
                metadata.put("height", mediaInfo.height());
                metadata.put("encodedWidth", frameWidth);
                metadata.put("jpegQuality", jpegQuality);
                metadata.put("auditMode", auditMode);
                images.add(new PreparedImage("image/jpeg", bytes, metadata));
                totalBytes += bytes.length;
            } catch (Exception ex) {
                log.info("Deep scene critic representative frame failed scene={} message={}", scene.get("id"), ex.getMessage());
            }
        }

        for (Integer boundaryIndex : sampledBoundaryIndexes(scenes, maxTransitionBoundaries)) {
            if (images.size() >= maxEvidenceImages - 1) {
                break;
            }
            Map<String, Object> fromScene = scenes.get(boundaryIndex);
            Map<String, Object> toScene = scenes.get(boundaryIndex + 1);
            double boundary = doubleValue(fromScene.get("end"), doubleValue(toScene.get("start"), 0.0));
            totalBytes = addTransitionImage(sourcePath, evidenceDir, images, totalBytes, fromScene, toScene, boundaryIndex, Math.max(0.0, boundary - 0.18), "before_boundary", mediaInfo, maxInlineBytes, frameWidth, jpegQuality, auditMode);
            if (images.size() >= maxEvidenceImages) {
                break;
            }
            totalBytes = addTransitionImage(sourcePath, evidenceDir, images, totalBytes, fromScene, toScene, boundaryIndex, boundary + 0.18, "after_boundary", mediaInfo, maxInlineBytes, frameWidth, jpegQuality, auditMode);
        }
        return images;
    }

    private long addTransitionImage(
            Path sourcePath,
            Path evidenceDir,
            List<PreparedImage> images,
            long totalBytes,
            Map<String, Object> fromScene,
            Map<String, Object> toScene,
            int boundaryIndex,
            double timestamp,
            String role,
            MediaInfo mediaInfo,
            int maxInlineBytes,
            int frameWidth,
            int jpegQuality,
            String auditMode
    ) {
        Path output = evidenceDir.resolve("transition-%03d-%s.jpg".formatted(boundaryIndex + 1, role));
        try {
            extractFrame(sourcePath, output, timestamp, frameWidth, jpegQuality);
            byte[] bytes = Files.readAllBytes(output);
            if (bytes.length == 0 || totalBytes + bytes.length > maxInlineBytes) {
                return totalBytes;
            }
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("kind", "transition_boundary");
            metadata.put("imageIndex", images.size() + 1);
            metadata.put("transitionId", "T%03d".formatted(boundaryIndex + 1));
            metadata.put("role", role);
            metadata.put("fromSceneId", stringValue(fromScene.get("id"), ""));
            metadata.put("toSceneId", stringValue(toScene.get("id"), ""));
            metadata.put("boundarySeconds", round3(doubleValue(fromScene.get("end"), timestamp)));
            metadata.put("timestampSeconds", round3(timestamp));
            metadata.put("source", "ffmpeg_transition_boundary_frame");
            metadata.put("bytes", bytes.length);
            metadata.put("width", mediaInfo.width());
            metadata.put("height", mediaInfo.height());
            metadata.put("encodedWidth", frameWidth);
            metadata.put("jpegQuality", jpegQuality);
            metadata.put("auditMode", auditMode);
            images.add(new PreparedImage("image/jpeg", bytes, metadata));
            return totalBytes + bytes.length;
        } catch (Exception ex) {
            log.info("Deep scene critic transition frame failed boundary={} role={} message={}", boundaryIndex + 1, role, ex.getMessage());
            return totalBytes;
        }
    }

    private List<Integer> sampledSceneIndexes(List<Map<String, Object>> scenes, int maxRepresentativeScenes) {
        TreeSet<Integer> indexes = new TreeSet<>();
        int size = scenes == null ? 0 : scenes.size();
        int maxScenes = Math.max(1, maxRepresentativeScenes);
        if (size == 0) {
            return new ArrayList<>();
        }
        for (int index = 0; index < Math.min(size, 4); index++) indexes.add(index);
        for (int index = Math.max(0, size - 3); index < size; index++) indexes.add(index);
        if (size > 7) {
            indexes.add(Math.max(0, size / 4));
            indexes.add(Math.max(0, size / 2));
            indexes.add(Math.max(0, (size * 3) / 4));
        }
        int step = Math.max(1, (int) Math.ceil((double) size / maxScenes));
        for (int index = 0; index < size && indexes.size() < maxScenes; index += step) {
            indexes.add(index);
        }
        return limitEvenly(indexes, maxScenes);
    }

    private List<Integer> sampledBoundaryIndexes(List<Map<String, Object>> scenes, int maxTransitionBoundaries) {
        TreeSet<Integer> indexes = new TreeSet<>();
        int size = scenes == null ? 0 : scenes.size();
        int maxBoundaries = Math.max(1, maxTransitionBoundaries);
        if (size <= 1) {
            return new ArrayList<>();
        }
        indexes.add(0);
        indexes.add(size - 2);
        if (size > 3) {
            indexes.add(Math.max(0, size / 4));
            indexes.add(Math.max(0, size / 2));
            indexes.add(Math.max(0, (size * 3) / 4));
        }
        int step = Math.max(1, (int) Math.ceil((double) (size - 1) / maxBoundaries));
        for (int index = 0; index < size - 1 && indexes.size() < maxBoundaries; index += step) {
            indexes.add(index);
        }
        return limitEvenly(indexes, maxBoundaries);
    }

    private List<Integer> limitEvenly(TreeSet<Integer> indexes, int maxItems) {
        List<Integer> sorted = new ArrayList<>(indexes);
        if (sorted.size() <= maxItems) {
            return sorted;
        }
        if (maxItems <= 1) {
            return List.of(sorted.get(0));
        }
        TreeSet<Integer> limited = new TreeSet<>();
        limited.add(sorted.get(0));
        limited.add(sorted.get(sorted.size() - 1));
        for (int slot = 1; slot < maxItems - 1; slot++) {
            int index = (int) Math.round((slot * (sorted.size() - 1.0)) / Math.max(1, maxItems - 1));
            limited.add(sorted.get(Math.max(0, Math.min(sorted.size() - 1, index))));
        }
        for (Integer index : sorted) {
            if (limited.size() >= maxItems) {
                break;
            }
            limited.add(index);
        }
        return new ArrayList<>(limited);
    }

    private double representativeTimestamp(Map<String, Object> scene) {
        double explicit = doubleValue(scene.get("representativeTimestamp"), -1.0);
        if (explicit >= 0) {
            return explicit;
        }
        double start = doubleValue(scene.get("start"), 0.0);
        double end = doubleValue(scene.get("end"), start + 1.0);
        return start + Math.max(0.0, end - start) / 2.0;
    }

    private void extractFrame(Path sourcePath, Path outputPath, double timestamp, int width, int quality) {
        run(List.of(
                "ffmpeg", "-hide_banner", "-y",
                "-ss", format(timestamp),
                "-i", sourcePath.toString(),
                "-frames:v", "1",
                "-vf", "scale=" + width + ":-2:force_original_aspect_ratio=decrease",
                "-q:v", String.valueOf(quality),
                outputPath.toString()
        ), "ffmpeg deep scene critic frame extraction");
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
            ), "ffprobe deep scene critic media info");
            List<String> lines = output.lines().map(String::trim).filter(line -> !line.isBlank()).toList();
            int width = lines.size() > 0 ? intValue(lines.get(0), 0) : 0;
            int height = lines.size() > 1 ? intValue(lines.get(1), 0) : 0;
            double duration = lines.size() > 2 ? doubleValue(lines.get(2), 0.0) : 0.0;
            return new MediaInfo(width, height, duration);
        } catch (RuntimeException ex) {
            log.info("Deep scene critic media probe failed: {}", ex.getMessage());
            return new MediaInfo(0, 0, 0.0);
        }
    }
    private Map<String, Object> buildRequest(String prompt, List<PreparedImage> evidence) {
        List<Map<String, Object>> parts = new ArrayList<>();
        parts.add(Map.of("text", prompt));
        for (PreparedImage image : evidence) {
            parts.add(Map.of("inline_data", Map.of(
                    "mime_type", image.mimeType(),
                    "data", Base64.getEncoder().encodeToString(image.bytes())
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
            Map<String, Object> videoDna,
            List<Map<String, Object>> scenes,
            List<Map<String, Object>> frames,
            List<Map<String, Object>> transcript,
            MediaInfo mediaInfo,
            List<PreparedImage> evidence,
            boolean fastAuditMode
    ) {
        return """
                You are the independent deep-vision SCENE_CRITIC for a Generate Shorts backend pipeline.

                The previous SCENE_ANALYSIS detected boundaries and attached frame ids to transcript nodes. Your job is to audit that scene map visually. Do not just say it is valid. Inspect backgrounds, setting changes, lighting, camera angle, camera motion, subject/speaker continuity, slide/screen/product/B-roll changes, jump cuts, transition quality, and whether transcript nodes align with visual scenes.

                Source metadata:
                {
                  "shortVideoId": "%s",
                  "title": "%s",
                  "originalFileName": "%s",
                  "platform": "%s",
                  "assetId": "%s",
                  "durationSeconds": %s,
                  "width": %s,
                  "height": %s,
                  "sceneCriticAuditMode": "%s",
                  "fastAuditMode": %s,
                  "videoDna": %s
                }

                Scene digest:
                %s

                Transcript-scene digest:
                %s

                Existing frame metadata:
                %s

                Vision evidence images are appended after this text in the exact order listed here:
                %s

                Return JSON only with this exact top-level shape:
                {
                  "status": "PASS|WARN|FAIL",
                  "confidence": 0.0,
                  "sceneCoverageScore": 0,
                  "boundaryAccuracyScore": 0,
                  "visualContinuityScore": 0,
                  "backgroundConsistencyScore": 0,
                  "transcriptAlignmentScore": 0,
                  "shortReadinessScore": 0,
                  "sceneAudits": [
                    {
                      "sceneId": "scene-001",
                      "background": "where this appears to be filmed",
                      "setting": "studio|office|outdoor|screen_recording|stage|vehicle|mixed|unknown",
                      "subjects": ["speaker/product/screen/object"],
                      "cameraStyle": "locked_off|handheld|screen_capture|multi_camera|animated|unknown",
                      "lighting": "consistent|dim|overexposed|flicker|mixed|unknown",
                      "motion": "low|medium|high",
                      "visualRole": "talking_head|screen|broll|product|slide|reaction|gameplay|unknown",
                      "transcriptAlignment": "aligned|weak|misaligned|unknown",
                      "issues": [],
                      "repairActions": []
                    }
                  ],
                  "transitionAudits": [
                    {
                      "transitionId": "T001",
                      "fromSceneId": "scene-001",
                      "toSceneId": "scene-002",
                      "boundarySeconds": 12.3,
                      "type": "hard_cut|jump_cut|background_change|camera_move|slide_change|continuity|unknown",
                      "isBoundaryValid": true,
                      "risk": "low|medium|high",
                      "evidence": "visual reason",
                      "issues": [],
                      "repairActions": []
                    }
                  ],
                  "globalIssues": [],
                  "compressionGuidance": {
                    "avoidCuts": [{"start":0.0,"end":0.0,"reason":"..."}],
                    "preferredCutPoints": [{"time":0.0,"reason":"..."}],
                    "brollOpportunities": [{"sceneId":"scene-001","reason":"..."}],
                    "captionSafetyNotes": [],
                    "backgroundContinuityNotes": []
                  },
                  "summary": "one sentence staff-level verdict"
                }

                Scoring guide:
                - PASS only when scene coverage, boundaries, visual continuity, and transcript alignment are reliable enough for automated shorts.
                - WARN when issues are repairable or localized, such as questionable boundaries, minor lighting shifts, weak transcript alignment, or possible jump cuts.
                - FAIL when scene detection is visually unsafe for compression, such as major missing backgrounds, broken transitions, or scene/transcript mapping that would cause wrong cuts.
                - When fastAuditMode is true, you are seeing representative sampled evidence. Audit what is visible, flag uncertainty explicitly, and do not fail solely because every scene was not individually shown.
                - Think like staff reviewing a production pipeline, not a generic captioner.
                """.formatted(
                video == null || video.getId() == null ? "" : video.getId(),
                video == null ? "" : escape(video.getTitle()),
                video == null ? "" : escape(video.getOriginalFileName()),
                video == null ? "" : escape(video.getPlatform()),
                sourceAsset == null || sourceAsset.getId() == null ? "" : sourceAsset.getId(),
                round3(mediaInfo.durationSeconds()),
                mediaInfo.width(),
                mediaInfo.height(),
                auditMode(fastAuditMode),
                fastAuditMode,
                toJson(videoDna),
                compact(toJson(sceneDigest(scenes)), MAX_TRANSCRIPT_PROMPT_CHARS),
                compact(toJson(transcriptSceneDigest(transcript)), MAX_TRANSCRIPT_PROMPT_CHARS),
                compact(toJson(frameMetadataForPrompt(frames)), 9000),
                toJson(evidence.stream().map(PreparedImage::metadata).toList())
        );
    }

    private Map<String, Object> sceneDigest(List<Map<String, Object>> scenes) {
        Map<String, Object> digest = new LinkedHashMap<>();
        List<Map<String, Object>> items = new ArrayList<>();
        int index = 0;
        for (Map<String, Object> scene : scenes == null ? List.<Map<String, Object>>of() : scenes) {
            if (index >= 80) {
                break;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", scene.getOrDefault("id", "scene-" + (index + 1)));
            item.put("index", scene.getOrDefault("index", index + 1));
            item.put("start", scene.getOrDefault("start", 0));
            item.put("end", scene.getOrDefault("end", 0));
            item.put("durationSeconds", scene.getOrDefault("durationSeconds", 0));
            item.put("source", scene.getOrDefault("source", "unknown"));
            item.put("representativeTimestamp", scene.getOrDefault("representativeTimestamp", representativeTimestamp(scene)));
            item.put("frameIds", scene.getOrDefault("frameIds", List.of()));
            item.put("representativeFrameId", scene.getOrDefault("representativeFrameId", ""));
            items.add(item);
            index++;
        }
        digest.put("sceneCount", scenes == null ? 0 : scenes.size());
        digest.put("scenes", items);
        return digest;
    }

    private Map<String, Object> transcriptSceneDigest(List<Map<String, Object>> transcript) {
        Map<String, Object> digest = new LinkedHashMap<>();
        List<Map<String, Object>> sampled = new ArrayList<>();
        List<Map<String, Object>> safeTranscript = copyList(transcript);
        TreeSet<Integer> indexes = sampledTranscriptIndexes(safeTranscript.size());
        for (Integer index : indexes) {
            Map<String, Object> source = safeTranscript.get(index);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", source.getOrDefault("id", "n-" + (index + 1)));
            item.put("start", source.getOrDefault("start", 0));
            item.put("end", source.getOrDefault("end", 0));
            item.put("speaker", source.getOrDefault("speaker", "Speaker 1"));
            item.put("sceneId", source.getOrDefault("sceneId", ""));
            item.put("sceneStart", source.getOrDefault("sceneStart", ""));
            item.put("sceneEnd", source.getOrDefault("sceneEnd", ""));
            item.put("frames", source.getOrDefault("frames", List.of()));
            item.put("transcript", compact(stringValue(source.get("transcript"), ""), 700));
            sampled.add(item);
        }
        digest.put("nodeCount", safeTranscript.size());
        digest.put("durationSeconds", round3(transcriptDuration(safeTranscript)));
        digest.put("sampledNodes", sampled);
        return digest;
    }
    private TreeSet<Integer> sampledTranscriptIndexes(int size) {
        TreeSet<Integer> indexes = new TreeSet<>();
        if (size <= 0) {
            return indexes;
        }
        for (int index = 0; index < Math.min(4, size); index++) indexes.add(index);
        for (int index = Math.max(0, size - 4); index < size; index++) indexes.add(index);
        if (size > 8) {
            indexes.add(Math.max(0, size / 4));
            indexes.add(Math.max(0, size / 2));
            indexes.add(Math.max(0, (size * 3) / 4));
        }
        return indexes;
    }

    private List<Map<String, Object>> frameMetadataForPrompt(List<Map<String, Object>> frames) {
        List<Map<String, Object>> result = new ArrayList<>();
        int index = 0;
        for (Map<String, Object> frame : frames == null ? List.<Map<String, Object>>of() : frames) {
            if (index >= 80) {
                break;
            }
            Map<String, Object> copy = new LinkedHashMap<>(frame);
            copy.remove("thumbnailDataUrl");
            result.add(copy);
            index++;
        }
        return result;
    }

    private SceneCriticResult normalizeResponse(
            String model,
            String prompt,
            Map<String, Object> response,
            List<Map<String, Object>> scenes,
            List<Map<String, Object>> frames,
            List<Map<String, Object>> transcript,
            MediaInfo mediaInfo,
            List<PreparedImage> evidence,
            boolean fastAuditMode,
            long evidenceBytes,
            Map<String, Object> timingMs
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
        double confidence = clamp(doubleValue(output.get("confidence"), 0.0), 0.0, 1.0);
        Map<String, Object> critic = new LinkedHashMap<>();
        critic.put("status", status);
        critic.put("confidence", confidence);
        critic.put("sceneCoverageScore", clampInt(intValue(output.get("sceneCoverageScore"), 0), 0, 100));
        critic.put("boundaryAccuracyScore", clampInt(intValue(output.get("boundaryAccuracyScore"), 0), 0, 100));
        critic.put("visualContinuityScore", clampInt(intValue(output.get("visualContinuityScore"), 0), 0, 100));
        critic.put("backgroundConsistencyScore", clampInt(intValue(output.get("backgroundConsistencyScore"), 0), 0, 100));
        critic.put("transcriptAlignmentScore", clampInt(intValue(output.get("transcriptAlignmentScore"), 0), 0, 100));
        critic.put("shortReadinessScore", clampInt(intValue(output.get("shortReadinessScore"), 0), 0, 100));
        critic.put("sceneAudits", listValue(output.get("sceneAudits")));
        critic.put("transitionAudits", listValue(output.get("transitionAudits")));
        critic.put("globalIssues", listValue(output.get("globalIssues")));
        critic.put("compressionGuidance", mapValue(output.get("compressionGuidance")));
        critic.put("summary", defaultString(output.get("summary"), "Deep scene critic completed."));

        Map<String, Object> traceMetadata = new LinkedHashMap<>();
        traceMetadata.put("source", "gemini_deep_scene_critic");
        traceMetadata.put("auditMode", auditMode(fastAuditMode));
        traceMetadata.put("fastAuditMode", fastAuditMode);
        traceMetadata.put("sceneCount", scenes.size());
        traceMetadata.put("frameCount", frames.size());
        traceMetadata.put("transcriptNodeCount", transcript.size());
        traceMetadata.put("evidenceImageCount", evidence.size());
        traceMetadata.put("evidenceBytes", evidenceBytes);
        traceMetadata.put("timingMs", timingMs == null ? Map.of() : timingMs);
        traceMetadata.put("sceneCoverageScore", critic.get("sceneCoverageScore"));
        traceMetadata.put("boundaryAccuracyScore", critic.get("boundaryAccuracyScore"));
        traceMetadata.put("visualContinuityScore", critic.get("visualContinuityScore"));
        traceMetadata.put("transcriptAlignmentScore", critic.get("transcriptAlignmentScore"));
        traceMetadata.put("globalIssues", critic.get("globalIssues"));

        List<Map<String, Object>> trace = List.of(traceRow("SCENE_CRITIC", status, stringValue(critic.get("summary"), "Deep scene critic completed."), confidence, traceMetadata));

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "gemini_deep_scene_critic");
        metadata.put("status", status);
        metadata.put("generatedAt", OffsetDateTime.now().toString());
        metadata.put("model", model);
        metadata.put("auditMode", auditMode(fastAuditMode));
        metadata.put("fastAuditMode", fastAuditMode);
        metadata.put("timingMs", timingMs == null ? Map.of() : timingMs);
        metadata.put("durationSeconds", round3(mediaInfo.durationSeconds()));
        metadata.put("width", mediaInfo.width());
        metadata.put("height", mediaInfo.height());
        metadata.put("sceneCount", scenes.size());
        metadata.put("frameCount", frames.size());
        metadata.put("transcriptNodeCount", transcript.size());
        metadata.put("evidenceImageCount", evidence.size());
        metadata.put("evidenceBytes", evidenceBytes);
        metadata.put("evidence", evidence.stream().map(PreparedImage::metadata).toList());
        metadata.put("critic", critic);
        metadata.put("rawTextPreview", compact(rawText, 4000));

        return new SceneCriticResult(true, "gemini", model, critic, trace, evidence.stream().map(PreparedImage::metadata).toList(), tokenMetadata, costMetadata, metadata);
    }

    private SceneCriticResult skipped(String model, String status, String reason, List<Map<String, Object>> scenes, List<Map<String, Object>> frames) {
        Map<String, Object> critic = new LinkedHashMap<>();
        critic.put("status", status);
        critic.put("confidence", 0.2);
        critic.put("summary", reason);
        critic.put("globalIssues", List.of(reason));
        critic.put("sceneCoverageScore", 0);
        critic.put("boundaryAccuracyScore", 0);
        critic.put("visualContinuityScore", 0);
        critic.put("backgroundConsistencyScore", 0);
        critic.put("transcriptAlignmentScore", 0);
        critic.put("shortReadinessScore", 0);
        critic.put("compressionGuidance", Map.of("avoidCuts", List.of(), "preferredCutPoints", List.of(), "brollOpportunities", List.of(), "captionSafetyNotes", List.of(), "backgroundContinuityNotes", List.of()));

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "gemini_deep_scene_critic");
        metadata.put("status", status);
        metadata.put("reason", reason);
        metadata.put("generatedAt", OffsetDateTime.now().toString());
        metadata.put("sceneCount", scenes == null ? 0 : scenes.size());
        metadata.put("frameCount", frames == null ? 0 : frames.size());
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

        List<Map<String, Object>> trace = List.of(traceRow("SCENE_CRITIC", status, reason, 0.2, Map.of("source", "gemini_deep_scene_critic")));
        return new SceneCriticResult(false, "gemini", model, critic, trace, List.of(), tokenMetadata, costMetadata, metadata);
    }

    private SceneCriticResult completedSceneCriticFromSourceCache(
            CreatorShortVideo video,
            CreatorAsset sourceAsset,
            String model,
            String sceneFingerprint
    ) {
        Map<String, Object> checkpoint = sourceStageCacheService.findStageCache(video, sourceAsset, PROMPT_TYPE);
        if (!sceneCriticCheckpointMatches(checkpoint, sourceAsset, model, sceneFingerprint)) {
            return null;
        }
        Map<String, Object> critic = mapValue(checkpoint.get("sceneCritic"));
        if (critic.isEmpty()) {
            return null;
        }
        Map<String, Object> metadata = mapValue(checkpoint.get("metadata"));
        metadata.put("restoredFromSourceCache", true);
        metadata.put("sourceCacheVideoId", stringValue(checkpoint.get("sourceCacheVideoId"), ""));
        metadata.put("cacheRestoredAt", OffsetDateTime.now().toString());
        Map<String, Object> tokenMetadata = mapValue(checkpoint.get("tokenMetadata"));
        tokenMetadata.put("restoredFromSourceCache", true);
        Map<String, Object> costMetadata = mapValue(checkpoint.get("costMetadata"));
        costMetadata.put("restoredFromSourceCache", true);
        List<Map<String, Object>> evidenceMetadata = copyList(listOfMaps(checkpoint.get("evidenceMetadata")));
        List<Map<String, Object>> trace = List.of(traceRow(
                "SCENE_CRITIC",
                stringValue(critic.get("status"), "PASS"),
                "Deep scene critic restored from source-level cache; no new Gemini scene critic tokens were consumed.",
                doubleValue(critic.get("confidence"), 0.82),
                metadata
        ));
        log.info(
                "Deep scene critic restored completed source-level cache videoId={} sourceCacheVideoId={} fingerprint={}",
                video == null ? null : video.getId(),
                checkpoint.get("sourceCacheVideoId"),
                sceneFingerprint
        );
        return new SceneCriticResult(true, "gemini", model, critic, trace, evidenceMetadata, tokenMetadata, costMetadata, metadata);
    }

    private void persistSceneCriticSourceCache(
            CreatorShortVideo video,
            CreatorAsset sourceAsset,
            String model,
            String sceneFingerprint,
            SceneCriticResult result
    ) {
        if (video == null || video.getId() == null || result == null || !result.mediaBacked()) {
            return;
        }
        try {
            Map<String, Object> checkpoint = new LinkedHashMap<>();
            checkpoint.put("source", "gemini_deep_scene_critic");
            checkpoint.put("status", "COMPLETED");
            checkpoint.put("sourceAssetId", sourceAsset == null || sourceAsset.getId() == null ? "" : sourceAsset.getId().toString());
            checkpoint.put("model", model);
            checkpoint.put("sceneFingerprint", sceneFingerprint);
            checkpoint.put("sceneCritic", new LinkedHashMap<>(result.sceneCritic()));
            checkpoint.put("evidenceMetadata", copyList(result.evidenceMetadata()));
            checkpoint.put("tokenMetadata", new LinkedHashMap<>(result.tokenMetadata()));
            checkpoint.put("costMetadata", new LinkedHashMap<>(result.costMetadata()));
            checkpoint.put("metadata", new LinkedHashMap<>(result.metadata()));
            checkpoint.put("updatedAt", OffsetDateTime.now().toString());
            sourceStageCacheService.putStageCache(video.getId(), PROMPT_TYPE, checkpoint);
        } catch (RuntimeException ex) {
            log.warn("Could not persist scene critic source cache videoId={} message={}", video.getId(), ex.getMessage());
        }
    }

    private boolean sceneCriticCheckpointMatches(Map<String, Object> checkpoint, CreatorAsset sourceAsset, String model, String sceneFingerprint) {
        if (checkpoint == null || checkpoint.isEmpty()) {
            return false;
        }
        if (!"COMPLETED".equalsIgnoreCase(stringValue(checkpoint.get("status"), ""))) {
            return false;
        }
        String expectedSourceAssetId = sourceAsset == null || sourceAsset.getId() == null ? "" : sourceAsset.getId().toString();
        String checkpointSourceAssetId = stringValue(checkpoint.get("sourceAssetId"), "");
        if (!expectedSourceAssetId.isBlank() && !checkpointSourceAssetId.isBlank() && !expectedSourceAssetId.equals(checkpointSourceAssetId)) {
            return false;
        }
        if (!model.equals(stringValue(checkpoint.get("model"), ""))) {
            return false;
        }
        return sceneFingerprint.equals(stringValue(checkpoint.get("sceneFingerprint"), ""));
    }

    private String payloadFingerprint(Object payload) {
        return Integer.toHexString(toJson(payload).hashCode());
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

    private List<Map<String, Object>> listOfMaps(Object value) {
        if (!(value instanceof List<?> list)) {
            return new ArrayList<>();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            Map<String, Object> map = mapValue(item);
            if (!map.isEmpty()) {
                result.add(map);
            }
        }
        return result;
    }

    private List<Map<String, Object>> copyList(List<Map<String, Object>> value) {
        List<Map<String, Object>> copy = new ArrayList<>();
        if (value == null) return copy;
        for (Map<String, Object> item : value) {
            copy.add(item == null ? new LinkedHashMap<>() : new LinkedHashMap<>(item));
        }
        return copy;
    }

    private double transcriptDuration(List<Map<String, Object>> transcript) {
        double duration = 0.0;
        for (Map<String, Object> node : transcript == null ? List.<Map<String, Object>>of() : transcript) {
            duration = Math.max(duration, doubleValue(node.get("end"), 0.0));
        }
        return duration;
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
    private String normalizeStatus(Object value) {
        String status = stringValue(value, "WARN").trim().toUpperCase(Locale.ROOT);
        return switch (status) {
            case "PASS", "PASSED", "COMPLETED", "OK" -> "PASS";
            case "FAIL", "FAILED", "ERROR" -> "FAIL";
            default -> "WARN";
        };
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

    private int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
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

    private record PreparedImage(String mimeType, byte[] bytes, Map<String, Object> metadata) {
    }

    public record SceneCriticResult(
            boolean mediaBacked,
            String provider,
            String model,
            Map<String, Object> sceneCritic,
            List<Map<String, Object>> trace,
            List<Map<String, Object>> evidenceMetadata,
            Map<String, Object> tokenMetadata,
            Map<String, Object> costMetadata,
            Map<String, Object> metadata
    ) {
    }
}
