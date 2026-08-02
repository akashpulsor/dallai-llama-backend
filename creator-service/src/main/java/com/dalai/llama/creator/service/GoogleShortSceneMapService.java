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
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
public class GoogleShortSceneMapService {

    private static final Logger log = LoggerFactory.getLogger(GoogleShortSceneMapService.class);
    private static final String PROMPT_TYPE = "SHORTS_GEMINI_SCENE_MAP";
    private static final int MAX_INLINE_VIDEO_BYTES = 18 * 1024 * 1024;
    private static final int RESPONSE_MAX_IN_MEMORY_BYTES = 48 * 1024 * 1024;
    private static final long FILE_API_UPLOAD_TIMEOUT_MS = 3_600_000;
    private static final long FILE_API_POLL_TIMEOUT_MS = 300_000;
    private static final long FILE_API_POLL_INTERVAL_MS = 2_000;
    private static final double SINGLE_PASS_LIMIT_SECONDS = 15 * 60.0;
    private static final double CHUNK_SECONDS = 10 * 60.0;
    private static final int MAX_SCENES_PER_CHUNK = 80;
    private static final int MAX_TRANSCRIPT_NODES_PER_CHUNK = 120;

    private final CreatorProperties properties;
    private final CreatorAiPricingService pricingService;
    private final GeminiUsageMetadataParser usageMetadataParser;
    private final GoogleGenAiClientFactory googleGenAiClientFactory;
    private final GeminiRateLimitGuard geminiRateLimitGuard;
    private final ShortSourceStageCacheService sourceStageCacheService;
    private final ObjectMapper objectMapper;

    public GoogleShortSceneMapService(
            CreatorProperties properties,
            CreatorAiPricingService pricingService,
            GeminiUsageMetadataParser usageMetadataParser,
            GoogleGenAiClientFactory googleGenAiClientFactory,
            GeminiRateLimitGuard geminiRateLimitGuard,
            ShortSourceStageCacheService sourceStageCacheService,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.pricingService = pricingService;
        this.usageMetadataParser = usageMetadataParser;
        this.googleGenAiClientFactory = googleGenAiClientFactory;
        this.geminiRateLimitGuard = geminiRateLimitGuard;
        this.sourceStageCacheService = sourceStageCacheService;
        this.objectMapper = objectMapper;
    }

    public SceneMapResult mapScenes(
            CreatorShortVideo video,
            CreatorAsset sourceAsset,
            List<Map<String, Object>> transcript,
            Path sourceVideoPath
    ) {
        String model = stringValue(properties.getAi().getGeminiModel(), "gemini-2.5-flash");
        if (sourceVideoPath == null || !Files.isRegularFile(sourceVideoPath)) {
            return skipped(model, "Source video file was not available for Gemini scene map.");
        }

        try {
            long sourceBytes = Files.size(sourceVideoPath);
            String mimeType = normalizeVideoContentType(sourceAsset == null ? "" : sourceAsset.getContentType());
            double durationSeconds = Math.max(probeDurationSeconds(sourceVideoPath), transcriptDurationSeconds(transcript));
            List<Window> windows = analysisWindows(durationSeconds);
            SceneMapResult completedSourceCache = completedSceneMapFromSourceCache(video, sourceAsset, model, windows.size());
            if (completedSourceCache != null) {
                return completedSourceCache;
            }
            MediaReference mediaReference = prepareMediaReference(model, video, sourceVideoPath, sourceBytes, mimeType);

            List<Map<String, Object>> scenes = new ArrayList<>();
            List<Map<String, Object>> trace = new ArrayList<>();
            List<Map<String, Object>> chunks = new ArrayList<>();
            Map<Integer, SceneMapChunkResult> checkpointedChunks = loadSceneMapSourceCheckpoint(video, sourceAsset, model, windows.size());
            long totalInputTokens = 0L;
            long totalOutputTokens = 0L;
            long totalTokens = 0L;
            long startedAt = System.nanoTime();

            for (int index = 0; index < windows.size(); index++) {
                Window window = windows.get(index);
                double fps = fpsForWindow(durationSeconds, window);
                SceneMapChunkResult checkpointedChunk = checkpointedChunks.get(index);
                List<Map<String, Object>> chunkScenes;
                Map<String, Object> chunkMetadata;
                Map<String, Object> chunkTokenMetadata;
                if (checkpointedChunk != null) {
                    chunkScenes = checkpointedChunk.scenes();
                    chunkMetadata = new LinkedHashMap<>(checkpointedChunk.metadata());
                    chunkTokenMetadata = new LinkedHashMap<>(checkpointedChunk.tokenMetadata());
                    chunkMetadata.put("restoredFromSourceCache", true);
                } else {
                    String prompt = buildPrompt(video, window, durationSeconds, transcriptWindow(transcript, window), fps);
                    Map<String, Object> request = buildRequest(prompt, mediaReference, window, fps);
                    long chunkStartedAt = System.nanoTime();
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
                    Map<String, Object> usage = usageMetadataParser.parse(response == null ? null : response.get("usageMetadata"));
                    long fallbackInputTokens = pricingService.estimateTextTokens(prompt);
                    long inputTokens = longValue(usage.get("inputTokens"), fallbackInputTokens);
                    long outputTokens = longValue(usage.get("outputTokens"), 0L);
                    long chunkTokens = longValue(usage.get("totalTokens"), inputTokens + outputTokens);
                    Map<String, Object> parsed = parseResponseJson(response == null ? Map.of() : response);
                    chunkScenes = normalizeScenes(
                            listOfMaps(firstNonEmpty(parsed.get("scenes"), parsed.get("sceneMap"), parsed.get("segments"))),
                            window,
                            scenes.size()
                    );
                    chunkTokenMetadata = new LinkedHashMap<>();
                    chunkTokenMetadata.put("inputTokens", inputTokens);
                    chunkTokenMetadata.put("outputTokens", outputTokens);
                    chunkTokenMetadata.put("totalTokens", chunkTokens);
                    chunkTokenMetadata.put("providerTotalTokens", longValue(usage.get("totalTokens"), 0L));
                    chunkTokenMetadata.put("source", chunkTokens > 0 ? "PROVIDER_USAGE_METADATA" : "ESTIMATED_PROMPT_ONLY");
                    chunkMetadata = new LinkedHashMap<>();
                    chunkMetadata.put("index", index + 1);
                    chunkMetadata.put("chunkIndex", index);
                    chunkMetadata.put("start", round3(window.start()));
                    chunkMetadata.put("end", round3(window.end()));
                    chunkMetadata.put("fps", fps);
                    chunkMetadata.put("sceneCount", chunkScenes.size());
                    chunkMetadata.put("geminiMs", elapsedMs(chunkStartedAt));
                    chunkMetadata.put("usage", usage);
                    chunkMetadata.put("summary", stringValue(firstNonEmpty(parsed.get("windowSummary"), parsed.get("summary")), ""));
                    checkpointedChunk = new SceneMapChunkResult(chunkScenes, chunkTokenMetadata, chunkMetadata);
                    checkpointedChunks.put(index, checkpointedChunk);
                    persistSceneMapCheckpoint(video, sourceAsset, model, windows.size(), durationSeconds, sourceBytes, checkpointedChunks, false, List.of(), Map.of(), Map.of(), Map.of());
                }
                long inputTokens = longValue(chunkTokenMetadata.get("inputTokens"), 0L);
                long outputTokens = longValue(chunkTokenMetadata.get("outputTokens"), 0L);
                long chunkTokens = longValue(chunkTokenMetadata.get("totalTokens"), inputTokens + outputTokens);
                totalInputTokens += inputTokens;
                totalOutputTokens += outputTokens;
                totalTokens += chunkTokens;

                scenes.addAll(chunkScenes);
                chunks.add(chunkMetadata);
                log.info(
                        "Gemini scene map chunk complete videoId={} chunk={}/{} start={} end={} scenes={} restoredFromSourceCache={} geminiMs={}",
                        video == null ? null : video.getId(),
                        index + 1,
                        windows.size(),
                        round3(window.start()),
                        round3(window.end()),
                        chunkScenes.size(),
                        Boolean.TRUE.equals(chunkMetadata.get("restoredFromSourceCache")),
                        chunkMetadata.get("geminiMs")
                );
            }

            scenes = compactScenes(scenes, durationSeconds);
            if (scenes.isEmpty()) {
                return skipped(model, "Gemini scene map did not return any usable scenes.");
            }

            Map<String, Object> tokenMetadata = new LinkedHashMap<>();
            tokenMetadata.put("inputTokens", totalInputTokens);
            tokenMetadata.put("outputTokens", totalOutputTokens);
            tokenMetadata.put("totalTokens", totalTokens);
            tokenMetadata.put("source", totalTokens > 0 ? "PROVIDER_USAGE_METADATA" : "ESTIMATED_PROMPT_ONLY");

            Map<String, Object> costMetadata = pricingService.estimateTextCall(
                    "gemini",
                    model,
                    PROMPT_TYPE,
                    totalInputTokens,
                    totalOutputTokens,
                    stringValue(tokenMetadata.get("source"), "ESTIMATED_PROMPT_ONLY")
            );
            costMetadata.put("provider", "gemini");
            costMetadata.put("model", model);
            costMetadata.put("operation", PROMPT_TYPE);
            costMetadata.put("googleGenaiBackend", googleGenAiClientFactory.backend());

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("source", "gemini_video_scene_map");
            metadata.put("status", "COMPLETED");
            metadata.put("provider", "gemini");
            metadata.put("model", model);
            metadata.put("googleGenaiBackend", googleGenAiClientFactory.backend());
            metadata.put("generatedAt", OffsetDateTime.now().toString());
            metadata.put("durationSeconds", round3(durationSeconds));
            metadata.put("sourceSizeBytes", sourceBytes);
            metadata.put("chunkCount", windows.size());
            metadata.put("chunks", chunks);
            metadata.put("sceneCount", scenes.size());
            metadata.put("frameCount", 0);
            metadata.put("mediaReference", mediaReference.metadata());
            metadata.put("tokenMetadata", tokenMetadata);
            metadata.put("costMetadata", costMetadata);
            metadata.put("timingMs", Map.of("total", elapsedMs(startedAt)));
            metadata.put("contract", Map.of(
                    "rendererCutsSource", "ffmpeg_final_render_only",
                    "sceneBoundarySource", "gemini_timestamped_scene_json",
                    "frameExtraction", "not_used_for_scene_map"
            ));
            persistSceneMapCheckpoint(video, sourceAsset, model, windows.size(), durationSeconds, sourceBytes, checkpointedChunks, true, scenes, tokenMetadata, costMetadata, metadata);

            trace.add(traceRow(
                    "SCENE_ANALYSIS",
                    "COMPLETED",
                    "Gemini generated timestamped scene map JSON without backend frame-by-frame scene extraction.",
                    0.82,
                    metadata
            ));
            return new SceneMapResult(
                    true,
                    "gemini",
                    model,
                    new ShortSceneAnalysisService.VisualSceneAnalysisResult(true, scenes, List.of(), trace, metadata),
                    tokenMetadata,
                    costMetadata,
                    metadata
            );
        } catch (Exception ex) {
            log.warn("Gemini scene map failed videoId={} message={}", video == null ? null : video.getId(), ex.getMessage());
            return skipped(model, "Gemini scene map failed: " + safeMessage(ex));
        }
    }

    private MediaReference prepareMediaReference(
            String model,
            CreatorShortVideo video,
            Path sourceVideoPath,
            long sourceBytes,
            String mimeType
    ) throws IOException {
        if (!googleGenAiClientFactory.useVertexAi()) {
            UploadedVideoFile uploaded = uploadVideoFile(model, video, sourceVideoPath, sourceBytes, mimeType);
            return new MediaReference("file_data", uploaded.fileUri(), mimeType, null, Map.of(
                    "source", "gemini_files_api",
                    "fileName", uploaded.fileName(),
                    "sourceBytes", sourceBytes
            ));
        }
        if (sourceBytes > MAX_INLINE_VIDEO_BYTES) {
            throw new IllegalStateException("Gemini scene map for large videos currently requires AI Studio Files API. Vertex inline limit would be exceeded by " + sourceBytes + " bytes.");
        }
        byte[] bytes = Files.readAllBytes(sourceVideoPath);
        return new MediaReference("inline_data", "", mimeType, bytes, Map.of(
                "source", "inline_data",
                "sourceBytes", sourceBytes,
                "bytesSentToGoogle", bytes.length
        ));
    }

    private Map<String, Object> buildRequest(String prompt, MediaReference mediaReference, Window window, double fps) {
        List<Map<String, Object>> parts = new ArrayList<>();
        Map<String, Object> mediaPart = new LinkedHashMap<>();
        if ("file_data".equals(mediaReference.kind())) {
            mediaPart.put("file_data", Map.of(
                    "mime_type", mediaReference.mimeType(),
                    "file_uri", mediaReference.fileUri()
            ));
        } else {
            mediaPart.put("inline_data", Map.of(
                    "mime_type", mediaReference.mimeType(),
                    "data", Base64.getEncoder().encodeToString(mediaReference.bytes())
            ));
        }
        mediaPart.put("video_metadata", Map.of(
                "start_offset", formatOffset(window.start()),
                "end_offset", formatOffset(window.end()),
                "fps", fps
        ));
        parts.add(mediaPart);
        parts.add(Map.of("text", prompt));

        Map<String, Object> generationConfig = new LinkedHashMap<>();
        generationConfig.put("responseMimeType", "application/json");
        if (properties.getAi().getMaxOutputTokens() != null && properties.getAi().getMaxOutputTokens() > 0) {
            generationConfig.put("maxOutputTokens", properties.getAi().getMaxOutputTokens());
        }

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("systemInstruction", Map.of(
                "parts", List.of(Map.of("text", "Return only one valid JSON object. Do not wrap it in markdown. Do not invent timestamps outside the requested video window."))
        ));
        request.put("contents", List.of(Map.of(
                "role", "user",
                "parts", parts
        )));
        request.put("generationConfig", generationConfig);
        return request;
    }

    private String buildPrompt(
            CreatorShortVideo video,
            Window window,
            double durationSeconds,
            List<Map<String, Object>> transcriptWindow,
            double fps
    ) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("videoId", video == null || video.getId() == null ? "" : video.getId().toString());
        input.put("title", video == null ? "" : stringValue(video.getTitle(), ""));
        input.put("windowStartSeconds", round3(window.start()));
        input.put("windowEndSeconds", round3(window.end()));
        input.put("sourceDurationSeconds", round3(durationSeconds));
        input.put("fps", fps);
        input.put("transcriptWindow", transcriptWindow);
        input.put("requiredSceneFields", List.of(
                "sceneId",
                "start",
                "end",
                "visualSummary",
                "activeSpeaker",
                "shotType",
                "transitionReason",
                "continuityNotes",
                "bRollOpportunity",
                "captionSafeArea"
        ));
        return """
                You are the visual scene-map worker for a shorts-generation system.

                Analyze only this requested window of the attached video. Return timestamped scene JSON.

                Rules:
                - Return start and end as absolute seconds from the original source video, not local chunk time.
                - Split scenes when visual composition, shot/framing, speaker focus, setting, topic support visual, or continuity changes.
                - Do not create micro-scenes below 1 second unless there is a hard cut.
                - Prefer fewer, useful edit scenes over frame-by-frame listing.
                - Use transcript only to identify active speaker and spoken context; visual boundaries must come from the video.
                - If uncertain, set confidence lower and explain in continuityNotes.
                - Do not claim the video was physically cut. You are returning a scene map only.

                Return one JSON object:
                {
                  "windowSummary": "short summary",
                  "scenes": [
                    {
                      "sceneId": "scene id unique within this response",
                      "start": 0.0,
                      "end": 3.2,
                      "visualSummary": "what is visible",
                      "activeSpeaker": "speaker label or empty",
                      "shotType": "talking_head|two_shot|screen|b_roll|wide|close_up|text_slide|black_screen|other",
                      "transitionReason": "why this scene begins/ends",
                      "continuityNotes": "visual continuity and any risk",
                      "bRollOpportunity": "how this scene can support a short or empty",
                      "captionSafeArea": "top|middle|bottom|unknown",
                      "confidence": 0.0
                    }
                  ]
                }

                INPUT:
                %s
                """.formatted(toJson(input));
    }

    private List<Window> analysisWindows(double durationSeconds) {
        double duration = durationSeconds > 0 ? durationSeconds : SINGLE_PASS_LIMIT_SECONDS;
        double step = duration <= SINGLE_PASS_LIMIT_SECONDS ? duration : CHUNK_SECONDS;
        List<Window> windows = new ArrayList<>();
        for (double start = 0.0; start < duration - 0.1; start += step) {
            double end = Math.min(duration, start + step);
            windows.add(new Window(round3(start), round3(end)));
        }
        return windows.isEmpty() ? List.of(new Window(0.0, SINGLE_PASS_LIMIT_SECONDS)) : windows;
    }

    private SceneMapResult completedSceneMapFromSourceCache(CreatorShortVideo video, CreatorAsset sourceAsset, String model, int chunkCount) {
        Map<String, Object> checkpoint = sourceStageCacheService.findStageCache(video, sourceAsset, PROMPT_TYPE);
        if (!sceneMapCheckpointMatches(checkpoint, sourceAsset, model, chunkCount)
                || !"COMPLETED".equalsIgnoreCase(stringValue(checkpoint.get("status"), ""))) {
            return null;
        }
        List<Map<String, Object>> scenes = listOfMaps(checkpoint.get("scenes"));
        if (scenes.isEmpty()) {
            return null;
        }
        Map<String, Object> tokenMetadata = mapValue(checkpoint.get("tokenMetadata"));
        tokenMetadata.put("restoredFromSourceCache", true);
        Map<String, Object> costMetadata = mapValue(checkpoint.get("costMetadata"));
        costMetadata.put("restoredFromSourceCache", true);
        Map<String, Object> metadata = mapValue(checkpoint.get("metadata"));
        if (metadata.isEmpty()) {
            metadata.put("source", "gemini_video_scene_map");
            metadata.put("status", "COMPLETED");
            metadata.put("model", model);
        }
        metadata.put("restoredFromSourceCache", true);
        metadata.put("sourceCacheVideoId", stringValue(checkpoint.get("sourceCacheVideoId"), ""));
        metadata.put("cacheRestoredAt", OffsetDateTime.now().toString());
        List<Map<String, Object>> trace = List.of(traceRow(
                "SCENE_ANALYSIS",
                "COMPLETED",
                "Gemini scene map restored from source-level cache; no new Gemini scene-map tokens were consumed.",
                0.84,
                metadata
        ));
        log.info(
                "Gemini scene map restored completed source-level cache videoId={} sourceCacheVideoId={} scenes={} chunkCount={}",
                video == null ? null : video.getId(),
                checkpoint.get("sourceCacheVideoId"),
                scenes.size(),
                chunkCount
        );
        return new SceneMapResult(
                true,
                "gemini",
                model,
                new ShortSceneAnalysisService.VisualSceneAnalysisResult(true, scenes, List.of(), trace, metadata),
                tokenMetadata,
                costMetadata,
                metadata
        );
    }

    private Map<Integer, SceneMapChunkResult> loadSceneMapSourceCheckpoint(CreatorShortVideo video, CreatorAsset sourceAsset, String model, int chunkCount) {
        Map<String, Object> checkpoint = sourceStageCacheService.findStageCache(video, sourceAsset, PROMPT_TYPE);
        if (!sceneMapCheckpointMatches(checkpoint, sourceAsset, model, chunkCount)) {
            return new java.util.TreeMap<>();
        }
        Map<Integer, SceneMapChunkResult> chunks = sceneMapChunksFromCheckpoint(checkpoint, chunkCount);
        if (!chunks.isEmpty()) {
            log.info(
                    "Gemini scene map restored source-level chunk cache videoId={} sourceCacheVideoId={} completedChunks={} chunkCount={}",
                    video == null ? null : video.getId(),
                    checkpoint.get("sourceCacheVideoId"),
                    chunks.size(),
                    chunkCount
            );
        }
        return chunks;
    }

    private Map<Integer, SceneMapChunkResult> sceneMapChunksFromCheckpoint(Map<String, Object> checkpoint, int chunkCount) {
        Map<Integer, SceneMapChunkResult> chunks = new java.util.TreeMap<>();
        for (Map<String, Object> item : listOfMaps(checkpoint.get("chunks"))) {
            int chunkIndex = intValue(item.get("chunkIndex"), intValue(item.get("index"), 1) - 1);
            if (chunkIndex < 0 || (chunkCount > 0 && chunkIndex >= chunkCount)) {
                continue;
            }
            List<Map<String, Object>> scenes = listOfMaps(item.get("scenes"));
            Map<String, Object> tokenMetadata = mapValue(item.get("tokenMetadata"));
            Map<String, Object> metadata = mapValue(item.get("metadata"));
            if (scenes.isEmpty()) {
                continue;
            }
            metadata.put("checkpointRestored", true);
            chunks.put(chunkIndex, new SceneMapChunkResult(scenes, tokenMetadata, metadata));
        }
        return chunks;
    }

    private void persistSceneMapCheckpoint(
            CreatorShortVideo video,
            CreatorAsset sourceAsset,
            String model,
            int chunkCount,
            double durationSeconds,
            long sourceBytes,
            Map<Integer, SceneMapChunkResult> chunks,
            boolean completed,
            List<Map<String, Object>> scenes,
            Map<String, Object> tokenMetadata,
            Map<String, Object> costMetadata,
            Map<String, Object> metadata
    ) {
        if (video == null || video.getId() == null) {
            return;
        }
        try {
            Map<String, Object> checkpoint = new LinkedHashMap<>();
            checkpoint.put("source", "gemini_video_scene_map");
            checkpoint.put("status", completed ? "COMPLETED" : "RUNNING");
            checkpoint.put("sourceAssetId", sourceAsset == null || sourceAsset.getId() == null ? "" : sourceAsset.getId().toString());
            checkpoint.put("model", model);
            checkpoint.put("chunkSeconds", CHUNK_SECONDS);
            checkpoint.put("chunkCount", chunkCount);
            checkpoint.put("completedChunkCount", chunks == null ? 0 : chunks.size());
            checkpoint.put("durationSeconds", round3(durationSeconds));
            checkpoint.put("sourceSizeBytes", sourceBytes);
            checkpoint.put("activeStage", "SCENE_ANALYSIS");
            checkpoint.put("message", completed
                    ? "Gemini scene map complete"
                    : "Generating Gemini scene map chunk " + (chunks == null ? 0 : chunks.size()) + "/" + Math.max(1, chunkCount));
            checkpoint.put("updatedAt", OffsetDateTime.now().toString());
            List<Map<String, Object>> chunkPayload = new ArrayList<>();
            if (chunks != null) {
                for (Map.Entry<Integer, SceneMapChunkResult> entry : chunks.entrySet()) {
                    SceneMapChunkResult chunk = entry.getValue();
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("chunkIndex", entry.getKey());
                    item.put("scenes", chunk == null ? List.of() : copyList(chunk.scenes()));
                    item.put("tokenMetadata", chunk == null ? Map.of() : new LinkedHashMap<>(chunk.tokenMetadata()));
                    item.put("metadata", chunk == null ? Map.of() : new LinkedHashMap<>(chunk.metadata()));
                    chunkPayload.add(item);
                }
            }
            checkpoint.put("chunks", chunkPayload);
            if (completed) {
                checkpoint.put("scenes", scenes == null ? List.of() : copyList(scenes));
                checkpoint.put("sceneCount", scenes == null ? 0 : scenes.size());
                checkpoint.put("tokenMetadata", tokenMetadata == null ? Map.of() : new LinkedHashMap<>(tokenMetadata));
                checkpoint.put("costMetadata", costMetadata == null ? Map.of() : new LinkedHashMap<>(costMetadata));
                checkpoint.put("metadata", metadata == null ? Map.of() : new LinkedHashMap<>(metadata));
            }
            sourceStageCacheService.putStageCache(video.getId(), PROMPT_TYPE, checkpoint);
        } catch (RuntimeException ex) {
            log.warn("Could not persist Gemini scene map source checkpoint videoId={} message={}", video.getId(), ex.getMessage());
        }
    }

    private boolean sceneMapCheckpointMatches(Map<String, Object> checkpoint, CreatorAsset sourceAsset, String model, int chunkCount) {
        if (checkpoint == null || checkpoint.isEmpty()) {
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
        if (intValue(checkpoint.get("chunkCount"), chunkCount) != chunkCount) {
            return false;
        }
        return doubleValue(checkpoint.get("chunkSeconds"), CHUNK_SECONDS) == CHUNK_SECONDS;
    }

    private double fpsForWindow(double durationSeconds, Window window) {
        double windowDuration = Math.max(0.1, window.end() - window.start());
        if (durationSeconds > 2 * 60 * 60) {
            return 0.2;
        }
        if (windowDuration >= CHUNK_SECONDS) {
            return 0.5;
        }
        return 1.0;
    }

    private List<Map<String, Object>> normalizeScenes(List<Map<String, Object>> rawScenes, Window window, int existingCount) {
        List<Map<String, Object>> normalized = new ArrayList<>();
        int index = existingCount + 1;
        for (Map<String, Object> raw : rawScenes == null ? List.<Map<String, Object>>of() : rawScenes) {
            double start = doubleValue(firstNonEmpty(raw.get("start"), raw.get("startSeconds"), raw.get("sourceStart")), -1.0);
            double end = doubleValue(firstNonEmpty(raw.get("end"), raw.get("endSeconds"), raw.get("sourceEnd")), -1.0);
            if (start < window.start() - 0.5 && end <= (window.end() - window.start()) + 0.5) {
                start += window.start();
                end += window.start();
            }
            start = clamp(start, window.start(), window.end());
            end = clamp(end, start + 0.25, window.end());
            if (end <= start + 0.1) {
                continue;
            }
            String sceneId = "gemini-scene-%03d".formatted(index);
            Map<String, Object> scene = new LinkedHashMap<>();
            scene.put("id", sceneId);
            scene.put("sceneId", sceneId);
            scene.put("index", index);
            scene.put("start", round3(start));
            scene.put("end", round3(end));
            scene.put("durationSeconds", round3(end - start));
            scene.put("label", stringValue(firstNonEmpty(raw.get("label"), raw.get("shotType")), "Scene " + index));
            scene.put("visualSummary", stringValue(raw.get("visualSummary"), ""));
            scene.put("summary", stringValue(firstNonEmpty(raw.get("visualSummary"), raw.get("summary")), ""));
            scene.put("activeSpeaker", stringValue(raw.get("activeSpeaker"), ""));
            scene.put("shotType", stringValue(raw.get("shotType"), "other"));
            scene.put("transitionReason", stringValue(raw.get("transitionReason"), ""));
            scene.put("continuityNotes", stringValue(raw.get("continuityNotes"), ""));
            scene.put("bRollOpportunity", stringValue(raw.get("bRollOpportunity"), ""));
            scene.put("captionSafeArea", stringValue(raw.get("captionSafeArea"), "unknown"));
            scene.put("confidence", clamp(doubleValue(raw.get("confidence"), 0.7), 0.0, 1.0));
            scene.put("source", "gemini_video_scene_map");
            scene.put("evidenceMode", "GEMINI_VIDEO_UNDERSTANDING");
            scene.put("representativeTimestamp", round3(start + Math.max(0.0, end - start) / 2.0));
            scene.put("frameIds", new ArrayList<String>());
            normalized.add(scene);
            index++;
        }
        return normalized;
    }

    private List<Map<String, Object>> compactScenes(List<Map<String, Object>> scenes, double durationSeconds) {
        List<Map<String, Object>> sorted = new ArrayList<>(scenes == null ? List.of() : scenes);
        sorted.sort(Comparator.comparingDouble(scene -> doubleValue(scene.get("start"), 0.0)));
        List<Map<String, Object>> compacted = new ArrayList<>();
        double cursor = 0.0;
        int index = 1;
        for (Map<String, Object> scene : sorted) {
            Map<String, Object> copy = new LinkedHashMap<>(scene);
            double start = Math.max(cursor, doubleValue(copy.get("start"), cursor));
            double end = Math.max(start + 0.25, doubleValue(copy.get("end"), start + 1.0));
            if (durationSeconds > 0) {
                start = Math.min(start, durationSeconds);
                end = Math.min(end, durationSeconds);
            }
            if (end <= start + 0.1) {
                continue;
            }
            String id = "gemini-scene-%03d".formatted(index);
            copy.put("id", id);
            copy.put("sceneId", id);
            copy.put("index", index);
            copy.put("start", round3(start));
            copy.put("end", round3(end));
            copy.put("durationSeconds", round3(end - start));
            copy.put("representativeTimestamp", round3(start + Math.max(0.0, end - start) / 2.0));
            compacted.add(copy);
            cursor = end;
            index++;
        }
        return compacted;
    }

    private UploadedVideoFile uploadVideoFile(String model, CreatorShortVideo video, Path sourcePath, long sourceBytes, String contentType) {
        WebClient client = googleGenAiClientFactory.client(RESPONSE_MAX_IN_MEMORY_BYTES);
        String uploadEndpoint = googleGenAiClientFactory.baseUrl().replaceFirst("/v1beta/?$", "/upload/v1beta") + "/files";
        String displayName = "shorts-scene-map-" + (video == null || video.getId() == null ? java.util.UUID.randomUUID() : video.getId());
        String mimeType = normalizeVideoContentType(contentType);

        ResponseEntity<Void> startResponse = client
                .post()
                .uri(URI.create(uploadEndpoint))
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Goog-Upload-Protocol", "resumable")
                .header("X-Goog-Upload-Command", "start")
                .header("X-Goog-Upload-Header-Content-Length", String.valueOf(sourceBytes))
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
                .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(sourceBytes))
                .header("X-Goog-Upload-Offset", "0")
                .header("X-Goog-Upload-Command", "upload, finalize")
                .body(BodyInserters.fromResource(new FileSystemResource(sourcePath)))
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {
                })
                .block(Duration.ofMillis(Math.max(properties.getAi().getTimeoutMs(), FILE_API_UPLOAD_TIMEOUT_MS)));

        Map<String, Object> file = mapValue(uploadResponse == null ? null : uploadResponse.get("file"));
        if (file.isEmpty() && uploadResponse != null) {
            file = mapValue(uploadResponse);
        }
        file = waitForActiveFile(client, file);
        String fileUri = stringValue(file.get("uri"), "");
        if (fileUri.isBlank()) {
            throw new IllegalStateException("Gemini Files API upload completed without a file URI.");
        }
        log.info("Uploaded source video to Gemini Files API for scene map model={} videoId={} fileName={} state={} sourceBytes={}",
                model,
                video == null ? null : video.getId(),
                file.get("name"),
                file.get("state"),
                sourceBytes);
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

    private Map<String, Object> parseResponseJson(Map<String, Object> response) {
        String text = extractText(response);
        if (text.isBlank()) {
            return new LinkedHashMap<>();
        }
        String cleaned = cleanJson(text);
        try {
            return objectMapper.readValue(cleaned, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (JsonProcessingException ex) {
            log.info("Gemini scene map JSON parse failed: {}", ex.getMessage());
            return new LinkedHashMap<>();
        }
    }

    private String extractText(Map<String, Object> response) {
        StringBuilder builder = new StringBuilder();
        for (Map<String, Object> candidate : listOfMaps(response.get("candidates"))) {
            Map<String, Object> content = mapValue(candidate.get("content"));
            for (Map<String, Object> part : listOfMaps(content.get("parts"))) {
                String text = stringValue(part.get("text"), "");
                if (!text.isBlank()) {
                    if (!builder.isEmpty()) {
                        builder.append('\n');
                    }
                    builder.append(text);
                }
            }
        }
        return builder.toString().trim();
    }

    private String cleanJson(String value) {
        String text = stringValue(value, "").trim();
        if (text.startsWith("```")) {
            text = text.replaceFirst("^```(?:json)?\\s*", "");
            text = text.replaceFirst("\\s*```$", "");
        }
        int first = text.indexOf('{');
        int last = text.lastIndexOf('}');
        if (first >= 0 && last > first) {
            return text.substring(first, last + 1);
        }
        return text;
    }

    private SceneMapResult skipped(String model, String reason) {
        Map<String, Object> tokenMetadata = Map.of(
                "inputTokens", 0,
                "outputTokens", 0,
                "totalTokens", 0,
                "source", "NONE"
        );
        Map<String, Object> costMetadata = pricingService.estimateTextCall("gemini", model, PROMPT_TYPE, 0, 0, "NONE");
        costMetadata.put("provider", "gemini");
        costMetadata.put("model", model);
        costMetadata.put("operation", PROMPT_TYPE);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "gemini_video_scene_map");
        metadata.put("status", "SKIPPED");
        metadata.put("reason", reason);
        metadata.put("provider", "gemini");
        metadata.put("model", model);
        metadata.put("generatedAt", OffsetDateTime.now().toString());
        List<Map<String, Object>> trace = List.of(traceRow("SCENE_ANALYSIS", "WARN", reason, 0.25, metadata));
        return new SceneMapResult(
                false,
                "gemini",
                model,
                new ShortSceneAnalysisService.VisualSceneAnalysisResult(false, List.of(), List.of(), trace, metadata),
                tokenMetadata,
                costMetadata,
                metadata
        );
    }

    private List<Map<String, Object>> transcriptWindow(List<Map<String, Object>> transcript, Window window) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> node : transcript == null ? List.<Map<String, Object>>of() : transcript) {
            double start = doubleValue(firstNonEmpty(node.get("start"), node.get("sourceStart"), node.get("timelineStart")), 0.0);
            double end = doubleValue(firstNonEmpty(node.get("end"), node.get("sourceEnd"), node.get("timelineEnd")), start);
            if (end >= window.start() && start <= window.end()) {
                Map<String, Object> copy = new LinkedHashMap<>();
                copy.put("id", stringValue(firstNonEmpty(node.get("id"), node.get("nodeId")), ""));
                copy.put("start", round3(start));
                copy.put("end", round3(end));
                copy.put("speaker", stringValue(firstNonEmpty(node.get("speaker"), node.get("role")), ""));
                copy.put("text", truncate(stringValue(firstNonEmpty(node.get("transcript"), node.get("text"), node.get("summary")), ""), 260));
                result.add(copy);
                if (result.size() >= MAX_TRANSCRIPT_NODES_PER_CHUNK) {
                    break;
                }
            }
        }
        return result;
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
            log.info("Could not probe source video duration for Gemini scene map: {}", ex.getMessage());
            return 0.0;
        }
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
                throw new IllegalStateException(label + " failed: " + truncate(output, 2000));
            }
            return output;
        } catch (IOException ex) {
            throw new IllegalStateException(label + " is not available.", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(label + " was interrupted.", ex);
        }
    }

    private double transcriptDurationSeconds(List<Map<String, Object>> transcript) {
        double duration = 0.0;
        for (Map<String, Object> node : transcript == null ? List.<Map<String, Object>>of() : transcript) {
            duration = Math.max(duration, doubleValue(firstNonEmpty(node.get("end"), node.get("sourceEnd"), node.get("timelineEnd")), 0.0));
        }
        return duration;
    }

    private String normalizeVideoContentType(String contentType) {
        String normalized = stringValue(contentType, "video/mp4").toLowerCase(Locale.ROOT);
        if (normalized.contains("quicktime")) return "video/quicktime";
        if (normalized.contains("webm")) return "video/webm";
        if (normalized.contains("matroska") || normalized.contains("mkv")) return "video/x-matroska";
        if (!normalized.startsWith("video/")) return "video/mp4";
        return normalized;
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

    private String formatOffset(double seconds) {
        return format(round3(Math.max(0.0, seconds))) + "s";
    }

    private String format(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            return "{}";
        }
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

    private Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> {
                if (key != null) {
                    result.put(String.valueOf(key), item);
                }
            });
            return result;
        }
        return new LinkedHashMap<>();
    }

    private Object firstNonEmpty(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            if (value != null && !String.valueOf(value).isBlank()) {
                return value;
            }
        }
        return null;
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

    private long longValue(Object value, long fallback) {
        if (value instanceof Number number) {
            return Math.max(0L, number.longValue());
        }
        if (value != null && !String.valueOf(value).isBlank()) {
            try {
                return Math.max(0L, Long.parseLong(String.valueOf(value)));
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private int intValue(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null && !String.valueOf(value).isBlank()) {
            try {
                return Integer.parseInt(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private List<Map<String, Object>> copyList(List<Map<String, Object>> values) {
        List<Map<String, Object>> copy = new ArrayList<>();
        for (Map<String, Object> value : values == null ? List.<Map<String, Object>>of() : values) {
            copy.add(new LinkedHashMap<>(value == null ? Map.of() : value));
        }
        return copy;
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private double round3(double value) {
        return Math.round(value * 1000.0d) / 1000.0d;
    }

    private long elapsedMs(long startedAtNanos) {
        return TimeUnit.NANOSECONDS.toMillis(Math.max(0L, System.nanoTime() - startedAtNanos));
    }

    private String stringValue(Object value, String fallback) {
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }

    private String truncate(String value, int maxLength) {
        String safe = value == null ? "" : value;
        if (safe.length() <= maxLength) {
            return safe;
        }
        return safe.substring(0, Math.max(0, maxLength - 1)) + "...";
    }

    private String safeMessage(Exception ex) {
        return ex == null || ex.getMessage() == null ? "unknown error" : ex.getMessage();
    }

    private record Window(double start, double end) {
    }

    private record UploadedVideoFile(String fileUri, String mimeType, String fileName) {
    }

    private record MediaReference(String kind, String fileUri, String mimeType, byte[] bytes, Map<String, Object> metadata) {
    }

    private record SceneMapChunkResult(
            List<Map<String, Object>> scenes,
            Map<String, Object> tokenMetadata,
            Map<String, Object> metadata
    ) {
    }

    public record SceneMapResult(
            boolean mediaBacked,
            String provider,
            String model,
            ShortSceneAnalysisService.VisualSceneAnalysisResult visualAnalysis,
            Map<String, Object> tokenMetadata,
            Map<String, Object> costMetadata,
            Map<String, Object> metadata
    ) {
    }
}
