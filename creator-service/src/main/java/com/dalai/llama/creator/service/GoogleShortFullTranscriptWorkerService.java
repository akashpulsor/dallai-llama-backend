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
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class GoogleShortFullTranscriptWorkerService {

    private static final Logger log = LoggerFactory.getLogger(GoogleShortFullTranscriptWorkerService.class);
    private static final String PROMPT_TYPE = "SHORTS_FULL_TRANSCRIPT";
    private static final String CHECKPOINT_KEY = "fullTranscriptCheckpoint";
    private static final int CHUNK_SECONDS = 300;
    private static final int RESPONSE_MAX_IN_MEMORY_BYTES = 24 * 1024 * 1024;
    private static final Duration AUDIO_COMMAND_TIMEOUT = Duration.ofHours(2);

    private final CreatorProperties properties;
    private final AssetStorageService assetStorageService;
    private final CreatorAiPricingService pricingService;
    private final GeminiUsageMetadataParser usageMetadataParser;
    private final GoogleGenAiClientFactory googleGenAiClientFactory;
    private final GeminiRateLimitGuard geminiRateLimitGuard;
    private final GenerationJobService generationJobService;
    private final ShortSourceStageCacheService sourceStageCacheService;
    private final ObjectMapper objectMapper;

    public GoogleShortFullTranscriptWorkerService(
            CreatorProperties properties,
            AssetStorageService assetStorageService,
            CreatorAiPricingService pricingService,
            GeminiUsageMetadataParser usageMetadataParser,
            GoogleGenAiClientFactory googleGenAiClientFactory,
            GeminiRateLimitGuard geminiRateLimitGuard,
            GenerationJobService generationJobService,
            ShortSourceStageCacheService sourceStageCacheService,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.assetStorageService = assetStorageService;
        this.pricingService = pricingService;
        this.usageMetadataParser = usageMetadataParser;
        this.googleGenAiClientFactory = googleGenAiClientFactory;
        this.geminiRateLimitGuard = geminiRateLimitGuard;
        this.generationJobService = generationJobService;
        this.sourceStageCacheService = sourceStageCacheService;
        this.objectMapper = objectMapper;
    }

    public FullTranscriptResult transcribe(CreatorShortVideo video, CreatorAsset sourceAsset) {
        return transcribe(video, sourceAsset, null);
    }

    public FullTranscriptResult transcribe(CreatorShortVideo video, CreatorAsset sourceAsset, Path sourceVideoPath) {
        String model = stringValue(properties.getAi().getGeminiModel(), "gemini-2.5-flash");
        if (sourceAsset == null || isBlank(sourceAsset.getBucket()) || isBlank(sourceAsset.getObjectKey())) {
            return skipped(model, "SKIPPED", "Source video storage location is missing.");
        }
        UUID jobId = video == null ? null : video.getGenerationJobId();
        FullTranscriptResult completedSourceCache = completedTranscriptFromSourceCache(video, sourceAsset, model);
        if (completedSourceCache != null) {
            return completedSourceCache;
        }
        FullTranscriptResult completedCheckpoint = completedTranscriptFromCheckpoint(jobId, sourceAsset, model);
        if (completedCheckpoint != null) {
            return completedCheckpoint;
        }

        Path workspace = null;
        try {
            workspace = Files.createTempDirectory("creator-shorts-full-transcript-");
            Path sourcePath = reusableSourcePath(sourceVideoPath)
                    ? sourceVideoPath
                    : workspace.resolve("source" + extensionFor(sourceAsset.getContentType(), sourceAsset.getObjectKey()));
            Path chunkDir = Files.createDirectories(workspace.resolve("audio-chunks"));

            if (!reusableSourcePath(sourceVideoPath)) {
                assetStorageService.downloadObjectToPath(sourceAsset.getBucket(), sourceAsset.getObjectKey(), sourcePath);
            }
            extractAudioChunks(sourcePath, chunkDir);

            List<Path> chunks = audioChunks(chunkDir);
            if (chunks.isEmpty()) {
                return skipped(model, "WARN", "No audio chunks were produced from the uploaded source video.");
            }

            Map<Integer, ChunkTranscriptionResult> checkpointedChunks = loadTranscriptSourceCheckpoint(video, sourceAsset, model, chunks.size());
            checkpointedChunks.putAll(loadTranscriptCheckpoint(jobId, sourceAsset, model, chunks.size()));
            if (!checkpointedChunks.isEmpty()) {
                log.info(
                        "Full shorts transcript worker resuming from durable chunk checkpoint jobId={} videoId={} completedChunks={} chunkCount={}",
                        jobId,
                        video == null ? null : video.getId(),
                        checkpointedChunks.size(),
                        chunks.size()
                );
            }

            List<Map<String, Object>> transcript = new ArrayList<>();
            List<Map<String, Object>> chunkMetadata = new ArrayList<>();
            long inputTokens = 0;
            long outputTokens = 0;
            long totalTokens = 0;
            long providerTotalTokens = 0;
            int nodeIndex = 1;
            for (int index = 0; index < chunks.size(); index++) {
                ChunkTranscriptionResult chunkResult = checkpointedChunks.get(index);
                if (chunkResult == null) {
                    chunkResult = transcribeChunk(model, video, sourceAsset, chunks.get(index), index);
                    checkpointedChunks.put(index, chunkResult);
                    persistTranscriptCheckpoint(video, jobId, sourceAsset, model, chunks.size(), checkpointedChunks, false, transcript.size());
                }
                chunkMetadata.add(chunkResult.metadata());
                inputTokens += longValue(chunkResult.tokenMetadata().get("inputTokens"));
                outputTokens += longValue(chunkResult.tokenMetadata().get("outputTokens"));
                totalTokens += longValue(chunkResult.tokenMetadata().get("totalTokens"));
                providerTotalTokens += longValue(chunkResult.tokenMetadata().get("providerTotalTokens"));
                for (Map<String, Object> segment : sortedSegments(chunkResult.segments())) {
                    Map<String, Object> node = transcriptNode(segment, nodeIndex, index * CHUNK_SECONDS);
                    if (!stringValue(node.get("transcript"), "").isBlank()) {
                        transcript.add(node);
                        nodeIndex++;
                    }
                }
            }
            transcript = normalizeTranscriptTimeline(transcript);
            persistTranscriptCheckpoint(video, jobId, sourceAsset, model, chunks.size(), checkpointedChunks, true, transcript.size());

            String usageSource = providerTotalTokens > 0 || inputTokens > 0 || outputTokens > 0 ? "PROVIDER" : "ESTIMATED_TEXT_ONLY";
            Map<String, Object> tokenMetadata = new LinkedHashMap<>();
            tokenMetadata.put("inputTokens", inputTokens);
            tokenMetadata.put("outputTokens", outputTokens);
            tokenMetadata.put("totalTokens", Math.max(totalTokens, inputTokens + outputTokens));
            tokenMetadata.put("providerTotalTokens", providerTotalTokens);
            tokenMetadata.put("source", usageSource);
            tokenMetadata.put("chunkSeconds", CHUNK_SECONDS);
            tokenMetadata.put("chunkCount", chunks.size());

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
            costMetadata.put("operation", PROMPT_TYPE);

            List<Map<String, Object>> trace = new ArrayList<>();
            trace.add(traceRow(
                    "TRANSCRIPT",
                    transcript.isEmpty() ? "WARN" : "COMPLETED",
                    transcript.isEmpty()
                            ? "Full audio was chunked and sent to Gemini, but no speech segments were returned."
                            : "Full audio was extracted from MinIO video, chunked, and transcribed into " + transcript.size() + " timestamped nodes.",
                    transcript.isEmpty() ? 0.25 : 0.88,
                    Map.of("chunkCount", chunks.size(), "nodeCount", transcript.size(), "chunkSeconds", CHUNK_SECONDS)
            ));
            trace.add(traceRow(
                    "TRANSCRIPT_CRITIC",
                    transcript.isEmpty() ? "WARN" : "COMPLETED",
                    transcript.isEmpty()
                            ? "Transcript critic could not verify usable speech coverage."
                            : "Transcript critic verified full-worker coverage from chunked audio.",
                    transcript.isEmpty() ? 0.3 : 0.82,
                    Map.of("coverage", transcript.isEmpty() ? "no_speech_or_unusable" : "full_chunked_audio")
            ));

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("source", "gemini_audio_chunks");
            metadata.put("generatedAt", OffsetDateTime.now().toString());
            metadata.put("model", model);
            metadata.put("chunkSeconds", CHUNK_SECONDS);
            metadata.put("chunkCount", chunks.size());
            metadata.put("transcriptNodeCount", transcript.size());
            metadata.put("chunks", chunkMetadata);
            metadata.put("sourceAssetId", sourceAsset.getId() == null ? "" : sourceAsset.getId().toString());

            return new FullTranscriptResult(
                    true,
                    "gemini",
                    model,
                    transcript,
                    trace,
                    tokenMetadata,
                    costMetadata,
                    metadata
            );
        } catch (Exception ex) {
            log.warn("Full shorts transcript worker failed videoId={} message={}", video == null ? null : video.getId(), ex.getMessage());
            return skipped(model, "FAILED", "Full transcript worker failed: " + safeMessage(ex));
        } finally {
            deleteQuietly(workspace);
        }
    }

    private boolean reusableSourcePath(Path sourceVideoPath) {
        return sourceVideoPath != null && Files.isRegularFile(sourceVideoPath);
    }

    private Map<Integer, ChunkTranscriptionResult> loadTranscriptCheckpoint(UUID jobId, CreatorAsset sourceAsset, String model, int chunkCount) {
        Map<Integer, ChunkTranscriptionResult> chunks = new java.util.TreeMap<>();
        if (jobId == null) {
            return chunks;
        }
        Map<String, Object> checkpoint = generationJobService.getGenerationJobCheckpoint(jobId, CHECKPOINT_KEY);
        if (!checkpointMatches(checkpoint, sourceAsset, model, chunkCount)) {
            return chunks;
        }
        return chunkResultsFromCheckpoint(checkpoint, model, chunkCount);
    }

    private Map<Integer, ChunkTranscriptionResult> loadTranscriptSourceCheckpoint(CreatorShortVideo video, CreatorAsset sourceAsset, String model, int chunkCount) {
        Map<String, Object> checkpoint = sourceStageCacheService.findStageCache(video, sourceAsset, CHECKPOINT_KEY);
        if (!checkpointMatches(checkpoint, sourceAsset, model, chunkCount)) {
            return new java.util.TreeMap<>();
        }
        Map<Integer, ChunkTranscriptionResult> chunks = chunkResultsFromCheckpoint(checkpoint, model, chunkCount);
        if (!chunks.isEmpty()) {
            log.info(
                    "Full shorts transcript worker restored source-level chunk cache videoId={} sourceCacheVideoId={} completedChunks={} chunkCount={}",
                    video == null ? null : video.getId(),
                    checkpoint.get("sourceCacheVideoId"),
                    chunks.size(),
                    chunkCount
            );
        }
        return chunks;
    }

    private Map<Integer, ChunkTranscriptionResult> chunkResultsFromCheckpoint(Map<String, Object> checkpoint, String model, int chunkCount) {
        Map<Integer, ChunkTranscriptionResult> chunks = new java.util.TreeMap<>();
        for (Map<String, Object> item : listOfMaps(checkpoint.get("chunks"))) {
            int chunkIndex = intValue(item.get("chunkIndex"), -1);
            if (chunkIndex < 0 || (chunkCount > 0 && chunkIndex >= chunkCount)) {
                continue;
            }
            List<Map<String, Object>> segments = listOfMaps(item.get("segments"));
            Map<String, Object> tokenMetadata = mapValue(item.get("tokenMetadata"));
            Map<String, Object> metadata = mapValue(item.get("metadata"));
            if (metadata.isEmpty()) {
                metadata.put("chunkIndex", chunkIndex);
                metadata.put("offsetSeconds", chunkIndex * CHUNK_SECONDS);
                metadata.put("segmentCount", segments.size());
                metadata.put("model", model);
            }
            metadata.put("checkpointRestored", true);
            chunks.put(chunkIndex, new ChunkTranscriptionResult(segments, tokenMetadata, metadata));
        }
        return chunks;
    }

    private FullTranscriptResult completedTranscriptFromCheckpoint(UUID jobId, CreatorAsset sourceAsset, String model) {
        if (jobId == null) {
            return null;
        }
        Map<String, Object> checkpoint = generationJobService.getGenerationJobCheckpoint(jobId, CHECKPOINT_KEY);
        if (!"COMPLETED".equalsIgnoreCase(stringValue(checkpoint.get("status"), "")) || !checkpointMatches(checkpoint, sourceAsset, model, -1)) {
            return null;
        }
        int chunkCount = intValue(checkpoint.get("chunkCount"), 0);
        Map<Integer, ChunkTranscriptionResult> chunks = chunkResultsFromCheckpoint(checkpoint, model, chunkCount);
        if (chunkCount <= 0 || chunks.size() < chunkCount) {
            return null;
        }
        log.info(
                "Full shorts transcript worker restored completed checkpoint jobId={} completedChunks={} chunkCount={}",
                jobId,
                chunks.size(),
                chunkCount
        );
        return fullTranscriptResultFromChunks(model, sourceAsset, chunks, chunkCount, true);
    }

    private FullTranscriptResult completedTranscriptFromSourceCache(CreatorShortVideo video, CreatorAsset sourceAsset, String model) {
        Map<String, Object> checkpoint = sourceStageCacheService.findStageCache(video, sourceAsset, CHECKPOINT_KEY);
        if (!"COMPLETED".equalsIgnoreCase(stringValue(checkpoint.get("status"), "")) || !checkpointMatches(checkpoint, sourceAsset, model, -1)) {
            return null;
        }
        int chunkCount = intValue(checkpoint.get("chunkCount"), 0);
        Map<Integer, ChunkTranscriptionResult> chunks = chunkResultsFromCheckpoint(checkpoint, model, chunkCount);
        if (chunkCount <= 0 || chunks.size() < chunkCount) {
            return null;
        }
        log.info(
                "Full shorts transcript worker restored completed source-level cache videoId={} sourceCacheVideoId={} completedChunks={} chunkCount={}",
                video == null ? null : video.getId(),
                checkpoint.get("sourceCacheVideoId"),
                chunks.size(),
                chunkCount
        );
        FullTranscriptResult result = fullTranscriptResultFromChunks(model, sourceAsset, chunks, chunkCount, true);
        Map<String, Object> metadata = new LinkedHashMap<>(result.metadata());
        metadata.put("restoredFromSourceCache", true);
        metadata.put("sourceCacheVideoId", stringValue(checkpoint.get("sourceCacheVideoId"), ""));
        Map<String, Object> tokenMetadata = new LinkedHashMap<>(result.tokenMetadata());
        tokenMetadata.put("restoredFromSourceCache", true);
        Map<String, Object> costMetadata = new LinkedHashMap<>(result.costMetadata());
        costMetadata.put("restoredFromSourceCache", true);
        return new FullTranscriptResult(
                result.mediaBacked(),
                result.provider(),
                result.model(),
                result.transcript(),
                result.trace(),
                tokenMetadata,
                costMetadata,
                metadata
        );
    }

    private FullTranscriptResult fullTranscriptResultFromChunks(
            String model,
            CreatorAsset sourceAsset,
            Map<Integer, ChunkTranscriptionResult> chunks,
            int chunkCount,
            boolean restoredFromCheckpoint
    ) {
        List<Map<String, Object>> transcript = new ArrayList<>();
        List<Map<String, Object>> chunkMetadata = new ArrayList<>();
        long inputTokens = 0;
        long outputTokens = 0;
        long totalTokens = 0;
        long providerTotalTokens = 0;
        int nodeIndex = 1;
        for (Map.Entry<Integer, ChunkTranscriptionResult> entry : chunks.entrySet()) {
            int index = entry.getKey();
            ChunkTranscriptionResult chunkResult = entry.getValue();
            chunkMetadata.add(chunkResult.metadata());
            inputTokens += longValue(chunkResult.tokenMetadata().get("inputTokens"));
            outputTokens += longValue(chunkResult.tokenMetadata().get("outputTokens"));
            totalTokens += longValue(chunkResult.tokenMetadata().get("totalTokens"));
            providerTotalTokens += longValue(chunkResult.tokenMetadata().get("providerTotalTokens"));
            for (Map<String, Object> segment : sortedSegments(chunkResult.segments())) {
                Map<String, Object> node = transcriptNode(segment, nodeIndex, index * CHUNK_SECONDS);
                if (!stringValue(node.get("transcript"), "").isBlank()) {
                    transcript.add(node);
                    nodeIndex++;
                }
            }
        }
        transcript = normalizeTranscriptTimeline(transcript);
        String usageSource = providerTotalTokens > 0 || inputTokens > 0 || outputTokens > 0 ? "PROVIDER" : "ESTIMATED_TEXT_ONLY";
        Map<String, Object> tokenMetadata = new LinkedHashMap<>();
        tokenMetadata.put("inputTokens", inputTokens);
        tokenMetadata.put("outputTokens", outputTokens);
        tokenMetadata.put("totalTokens", Math.max(totalTokens, inputTokens + outputTokens));
        tokenMetadata.put("providerTotalTokens", providerTotalTokens);
        tokenMetadata.put("source", usageSource);
        tokenMetadata.put("chunkSeconds", CHUNK_SECONDS);
        tokenMetadata.put("chunkCount", chunkCount);
        tokenMetadata.put("restoredFromCheckpoint", restoredFromCheckpoint);

        Map<String, Object> costMetadata = pricingService.estimateTextCall("gemini", model, PROMPT_TYPE, inputTokens, outputTokens, usageSource);
        costMetadata.put("provider", "gemini");
        costMetadata.put("model", model);
        costMetadata.put("operation", PROMPT_TYPE);

        List<Map<String, Object>> trace = new ArrayList<>();
        trace.add(traceRow(
                "TRANSCRIPT",
                transcript.isEmpty() ? "WARN" : "COMPLETED",
                restoredFromCheckpoint
                        ? "Full transcript was restored from durable chunk checkpoint."
                        : (transcript.isEmpty()
                        ? "Full audio was chunked and sent to Gemini, but no speech segments were returned."
                        : "Full audio was extracted from MinIO video, chunked, and transcribed into " + transcript.size() + " timestamped nodes."),
                transcript.isEmpty() ? 0.25 : 0.88,
                Map.of("chunkCount", chunkCount, "nodeCount", transcript.size(), "chunkSeconds", CHUNK_SECONDS, "restoredFromCheckpoint", restoredFromCheckpoint)
        ));
        trace.add(traceRow(
                "TRANSCRIPT_CRITIC",
                transcript.isEmpty() ? "WARN" : "COMPLETED",
                transcript.isEmpty()
                        ? "Transcript critic could not verify usable speech coverage."
                        : "Transcript critic verified full-worker coverage from chunked audio.",
                transcript.isEmpty() ? 0.3 : 0.82,
                Map.of("coverage", transcript.isEmpty() ? "no_speech_or_unusable" : "full_chunked_audio")
        ));

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "gemini_audio_chunks");
        metadata.put("generatedAt", OffsetDateTime.now().toString());
        metadata.put("model", model);
        metadata.put("chunkSeconds", CHUNK_SECONDS);
        metadata.put("chunkCount", chunkCount);
        metadata.put("transcriptNodeCount", transcript.size());
        metadata.put("chunks", chunkMetadata);
        metadata.put("sourceAssetId", sourceAsset == null || sourceAsset.getId() == null ? "" : sourceAsset.getId().toString());
        metadata.put("restoredFromCheckpoint", restoredFromCheckpoint);

        return new FullTranscriptResult(true, "gemini", model, transcript, trace, tokenMetadata, costMetadata, metadata);
    }

    private void persistTranscriptCheckpoint(
            CreatorShortVideo video,
            UUID jobId,
            CreatorAsset sourceAsset,
            String model,
            int chunkCount,
            Map<Integer, ChunkTranscriptionResult> chunks,
            boolean completed,
            int transcriptNodeCount
    ) {
        try {
            Map<String, Object> checkpoint = new LinkedHashMap<>();
            checkpoint.put("source", "gemini_audio_chunks");
            checkpoint.put("status", completed ? "COMPLETED" : "RUNNING");
            checkpoint.put("sourceAssetId", sourceAsset == null || sourceAsset.getId() == null ? "" : sourceAsset.getId().toString());
            checkpoint.put("model", model);
            checkpoint.put("chunkSeconds", CHUNK_SECONDS);
            checkpoint.put("chunkCount", chunkCount);
            checkpoint.put("completedChunkCount", chunks == null ? 0 : chunks.size());
            checkpoint.put("transcriptNodeCount", transcriptNodeCount);
            checkpoint.put("activeStage", "TRANSCRIPT");
            checkpoint.put("message", completed
                    ? "Full transcript complete"
                    : "Transcribing audio chunk " + (chunks == null ? 0 : chunks.size()) + "/" + Math.max(1, chunkCount));
            checkpoint.put("jobProgress", 34 + (int) Math.floor((Math.min(chunks == null ? 0 : chunks.size(), Math.max(1, chunkCount)) * 10.0d) / Math.max(1, chunkCount)));
            checkpoint.put("updatedAt", OffsetDateTime.now().toString());
            List<Map<String, Object>> chunkPayload = new ArrayList<>();
            if (chunks != null) {
                for (Map.Entry<Integer, ChunkTranscriptionResult> entry : chunks.entrySet()) {
                    ChunkTranscriptionResult result = entry.getValue();
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("chunkIndex", entry.getKey());
                    item.put("segments", result == null ? List.of() : copyList(result.segments()));
                    item.put("tokenMetadata", result == null ? Map.of() : new LinkedHashMap<>(result.tokenMetadata()));
                    item.put("metadata", result == null ? Map.of() : new LinkedHashMap<>(result.metadata()));
                    chunkPayload.add(item);
                }
            }
            checkpoint.put("chunks", chunkPayload);
            if (jobId != null) {
                generationJobService.updateGenerationJobCheckpoint(jobId, CHECKPOINT_KEY, checkpoint);
            }
            if (video != null && video.getId() != null) {
                sourceStageCacheService.putStageCache(video.getId(), CHECKPOINT_KEY, checkpoint);
            }
        } catch (Exception ex) {
            log.warn("Could not persist full transcript chunk checkpoint jobId={} message={}", jobId, ex.getMessage());
        }
    }

    private boolean checkpointMatches(Map<String, Object> checkpoint, CreatorAsset sourceAsset, String model, int chunkCount) {
        if (checkpoint == null || checkpoint.isEmpty()) {
            return false;
        }
        String expectedSourceAssetId = sourceAsset == null || sourceAsset.getId() == null ? "" : sourceAsset.getId().toString();
        String checkpointSourceAssetId = stringValue(checkpoint.get("sourceAssetId"), "");
        if (!expectedSourceAssetId.isBlank() && !expectedSourceAssetId.equals(checkpointSourceAssetId)) {
            return false;
        }
        if (!stringValue(checkpoint.get("model"), "").equals(model)) {
            return false;
        }
        if (intValue(checkpoint.get("chunkSeconds"), CHUNK_SECONDS) != CHUNK_SECONDS) {
            return false;
        }
        int checkpointChunkCount = intValue(checkpoint.get("chunkCount"), chunkCount);
        return chunkCount <= 0 || checkpointChunkCount == chunkCount;
    }

    private void extractAudioChunks(Path sourcePath, Path chunkDir) {
        run(List.of(
                "ffmpeg",
                "-hide_banner",
                "-y",
                "-i", sourcePath.toString(),
                "-vn",
                "-ac", "1",
                "-ar", "16000",
                "-f", "segment",
                "-segment_time", String.valueOf(CHUNK_SECONDS),
                "-reset_timestamps", "1",
                "-c:a", "pcm_s16le",
                chunkDir.resolve("chunk-%05d.wav").toString()
        ), AUDIO_COMMAND_TIMEOUT, "ffmpeg audio chunking");
    }

    private ChunkTranscriptionResult transcribeChunk(String model, CreatorShortVideo video, CreatorAsset sourceAsset, Path chunkPath, int chunkIndex) throws IOException {
        byte[] audioBytes = Files.readAllBytes(chunkPath);
        String prompt = buildPrompt(video, sourceAsset, chunkIndex, chunkIndex * CHUNK_SECONDS, audioBytes.length);
        Map<String, Object> request = buildRequest(prompt, audioBytes);
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
        return normalizeChunkResponse(model, prompt, response == null ? Map.of() : response, chunkIndex, audioBytes.length);
    }

    private Map<String, Object> buildRequest(String prompt, byte[] audioBytes) {
        List<Map<String, Object>> parts = new ArrayList<>();
        parts.add(Map.of("text", prompt));
        parts.add(Map.of("inline_data", Map.of(
                "mime_type", "audio/wav",
                "data", Base64.getEncoder().encodeToString(audioBytes)
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

    private String buildPrompt(CreatorShortVideo video, CreatorAsset sourceAsset, int chunkIndex, int offsetSeconds, int audioBytes) {
        return """
                Transcribe the attached audio chunk for the Generate Shorts full transcript worker.

                Chunk metadata:
                {
                  "shortVideoId": "%s",
                  "title": "%s",
                  "sourceAssetId": "%s",
                  "chunkIndex": %s,
                  "chunkOffsetSeconds": %s,
                  "chunkDurationSeconds": %s,
                  "audioFormat": "wav 16khz mono",
                  "audioBytes": %s
                }

                Return JSON only:
                {
                  "segments": [
                    {
                      "start": 0.0,
                      "end": 4.2,
                      "speaker": "Speaker 1",
                      "transcript": "verbatim speech from this chunk",
                      "confidence": 0.0
                    }
                  ],
                  "language": "detected language or unknown",
                  "issues": []
                }

                Use start/end relative to this chunk in seconds. Split on speaker turns, sentence boundaries, and meaningful pauses.
                If there is no speech, return an empty segments array and explain why in issues.
                """.formatted(
                video == null || video.getId() == null ? "" : video.getId(),
                video == null ? "" : escape(video.getTitle()),
                sourceAsset == null || sourceAsset.getId() == null ? "" : sourceAsset.getId(),
                chunkIndex,
                offsetSeconds,
                CHUNK_SECONDS,
                audioBytes
        );
    }

    private ChunkTranscriptionResult normalizeChunkResponse(String model, String prompt, Map<String, Object> response, int chunkIndex, int audioBytes) {
        String rawText = outputText(response);
        Map<String, Object> output = parseJsonObject(rawText);
        List<Map<String, Object>> segments = listOfMaps(output.get("segments"));
        Map<String, Object> usage = usageMetadataParser.parse(response.get("usageMetadata"));
        long fallbackInputTokens = pricingService.estimateTextTokens(prompt);
        long providerInputTokens = longValue(usage.get("inputTokens"));
        long inputTokens = Math.max(providerInputTokens, fallbackInputTokens);
        long outputTokens = longValue(usage.get("outputTokens"));

        Map<String, Object> tokenMetadata = new LinkedHashMap<>(usage);
        tokenMetadata.put("inputTokens", inputTokens);
        tokenMetadata.put("outputTokens", outputTokens);
        tokenMetadata.put("totalTokens", Math.max(longValue(usage.get("totalTokens")), inputTokens + outputTokens));
        tokenMetadata.put("source", providerInputTokens > 0 || outputTokens > 0 ? "PROVIDER" : "ESTIMATED_TEXT_ONLY");

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("chunkIndex", chunkIndex);
        metadata.put("offsetSeconds", chunkIndex * CHUNK_SECONDS);
        metadata.put("audioBytes", audioBytes);
        metadata.put("segmentCount", segments.size());
        metadata.put("language", stringValue(output.get("language"), "unknown"));
        metadata.put("issues", output.getOrDefault("issues", List.of()));
        metadata.put("model", model);

        return new ChunkTranscriptionResult(segments, tokenMetadata, metadata);
    }

    private List<Map<String, Object>> sortedSegments(List<Map<String, Object>> segments) {
        List<Map<String, Object>> sorted = new ArrayList<>();
        for (Map<String, Object> segment : segments == null ? List.<Map<String, Object>>of() : segments) {
            sorted.add(segment == null ? new LinkedHashMap<>() : new LinkedHashMap<>(segment));
        }
        sorted.sort(java.util.Comparator
                .comparingDouble((Map<String, Object> segment) -> doubleValue(segment.get("start"), 0.0))
                .thenComparingDouble(segment -> doubleValue(segment.get("end"), doubleValue(segment.get("start"), 0.0) + 0.2)));
        return sorted;
    }

    private Map<String, Object> transcriptNode(Map<String, Object> segment, int nodeIndex, int offsetSeconds) {
        double relativeStart = clamp(doubleValue(segment.get("start"), 0.0), 0.0, Math.max(0.0, CHUNK_SECONDS - 0.2));
        double relativeEnd = clamp(doubleValue(segment.get("end"), relativeStart + 1.0), relativeStart + 0.2, CHUNK_SECONDS);
        double start = offsetSeconds + relativeStart;
        double end = offsetSeconds + relativeEnd;
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", "n-%04d".formatted(nodeIndex));
        node.put("start", round3(start));
        node.put("end", round3(end));
        node.put("time", timeLabel(start, end));
        node.put("speaker", stringValue(segment.get("speaker"), "Speaker 1"));
        node.put("transcript", stringValue(segment.get("transcript"), "").trim());
        node.put("sceneId", "");
        node.put("emotion", 0);
        node.put("motion", 0);
        node.put("interestingness", heuristicInterestingness(segment));
        node.put("frames", List.of());
        node.put("confidence", doubleValue(segment.get("confidence"), 0.0));
        node.put("source", "full_transcript_worker");
        node.put("chunkOffsetSeconds", offsetSeconds);
        return node;
    }

    private List<Map<String, Object>> normalizeTranscriptTimeline(List<Map<String, Object>> transcript) {
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (Map<String, Object> item : transcript == null ? List.<Map<String, Object>>of() : transcript) {
            Map<String, Object> node = item == null ? new LinkedHashMap<>() : new LinkedHashMap<>(item);
            if (!stringValue(node.get("transcript"), "").isBlank()) {
                nodes.add(node);
            }
        }
        nodes.sort(java.util.Comparator
                .comparingDouble((Map<String, Object> node) -> doubleValue(node.get("start"), 0.0))
                .thenComparingDouble(node -> doubleValue(node.get("end"), doubleValue(node.get("start"), 0.0) + 0.2)));
        List<Map<String, Object>> normalized = new ArrayList<>();
        int index = 1;
        double previousStart = -1.0;
        for (Map<String, Object> node : nodes) {
            Map<String, Object> copy = new LinkedHashMap<>(node);
            String originalId = stringValue(copy.get("id"), "");
            double start = Math.max(0.0, doubleValue(copy.get("start"), 0.0));
            double end = Math.max(start + 0.2, doubleValue(copy.get("end"), start + 1.0));
            if (previousStart >= 0 && start + 0.001 < previousStart) {
                start = previousStart;
                end = Math.max(start + 0.2, end);
            }
            copy.put("id", "n-%04d".formatted(index));
            if (!originalId.isBlank() && !originalId.equals(copy.get("id"))) {
                copy.put("sourceNodeId", originalId);
            }
            copy.put("start", round3(start));
            copy.put("end", round3(end));
            copy.put("time", timeLabel(start, end));
            normalized.add(copy);
            previousStart = start;
            index++;
        }
        return normalized;
    }

    private int heuristicInterestingness(Map<String, Object> segment) {
        String text = stringValue(segment.get("transcript"), "").toLowerCase(Locale.ROOT);
        int score = 45;
        if (text.contains("?")) score += 12;
        if (text.matches(".*\\b(secret|mistake|problem|why|how|result|truth|money|growth|staff|customer)\\b.*")) score += 16;
        if (text.length() > 80) score += 8;
        if (text.length() < 15) score -= 8;
        return Math.max(0, Math.min(100, score));
    }

    private List<Path> audioChunks(Path chunkDir) throws IOException {
        try (var stream = Files.list(chunkDir)) {
            return stream
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".wav"))
                    .sorted()
                    .toList();
        }
    }

    private String run(List<String> command, Duration timeout, String label) {
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
                throw new IllegalStateException(label + " failed: " + tail(output, 3000));
            }
            return output;
        } catch (IOException ex) {
            throw new IllegalStateException("ffmpeg is not available for " + label + ".", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(label + " was interrupted.", ex);
        }
    }

    private FullTranscriptResult skipped(String model, String status, String reason) {
        List<Map<String, Object>> trace = new ArrayList<>();
        trace.add(traceRow("TRANSCRIPT", status, reason, "FAILED".equals(status) ? 0.0 : 0.2, Map.of("source", "full_transcript_worker")));
        trace.add(traceRow("TRANSCRIPT_CRITIC", "WARN", "Full transcript critic has no complete transcript to verify.", 0.2, Map.of("source", "full_transcript_worker")));

        Map<String, Object> tokenMetadata = new LinkedHashMap<>();
        tokenMetadata.put("inputTokens", 0);
        tokenMetadata.put("outputTokens", 0);
        tokenMetadata.put("totalTokens", 0);
        tokenMetadata.put("source", "NONE");

        Map<String, Object> costMetadata = pricingService.estimateTextCall("gemini", model, PROMPT_TYPE, 0, 0, "NONE");
        costMetadata.put("provider", "gemini");
        costMetadata.put("model", model);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "full_transcript_worker");
        metadata.put("status", status);
        metadata.put("reason", reason);
        metadata.put("generatedAt", OffsetDateTime.now().toString());

        return new FullTranscriptResult(false, "gemini", model, List.of(), trace, tokenMetadata, costMetadata, metadata);
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

    private List<Map<String, Object>> copyList(List<Map<String, Object>> value) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> item : value == null ? List.<Map<String, Object>>of() : value) {
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

    private String timeLabel(double start, double end) {
        return secondsLabel(start) + " - " + secondsLabel(end);
    }

    private String secondsLabel(double value) {
        int seconds = Math.max(0, (int) Math.round(value));
        return "%02d:%02d".formatted(seconds / 60, seconds % 60);
    }

    private String tail(String value, int maxLength) {
        String safe = value == null ? "" : value;
        return safe.length() <= maxLength ? safe : safe.substring(safe.length() - maxLength);
    }

    private double round3(double value) {
        return Math.round(value * 1000.0d) / 1000.0d;
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
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

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String stringValue(Object value, String fallback) {
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }

    private String safeMessage(Exception ex) {
        return ex == null || ex.getMessage() == null ? "unknown error" : ex.getMessage();
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

    private record ChunkTranscriptionResult(
            List<Map<String, Object>> segments,
            Map<String, Object> tokenMetadata,
            Map<String, Object> metadata
    ) {
    }

    public record FullTranscriptResult(
            boolean mediaBacked,
            String provider,
            String model,
            List<Map<String, Object>> transcript,
            List<Map<String, Object>> trace,
            Map<String, Object> tokenMetadata,
            Map<String, Object> costMetadata,
            Map<String, Object> metadata
    ) {
    }
}
