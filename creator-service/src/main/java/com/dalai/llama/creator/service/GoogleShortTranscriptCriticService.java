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
public class GoogleShortTranscriptCriticService {

    private static final Logger log = LoggerFactory.getLogger(GoogleShortTranscriptCriticService.class);
    private static final String PROMPT_TYPE = "SHORTS_TRANSCRIPT_CRITIC";
    private static final String CHECKPOINT_KEY = "transcriptCriticCheckpoint";
    private static final int CHUNK_SECONDS = 300;
    private static final int RESPONSE_MAX_IN_MEMORY_BYTES = 24 * 1024 * 1024;
    private static final int MAX_TRANSCRIPT_CHARS_PER_CHUNK = 12000;
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

    public GoogleShortTranscriptCriticService(
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

    public TranscriptCriticResult critique(CreatorShortVideo video, CreatorAsset sourceAsset, List<Map<String, Object>> transcript) {
        return critique(video, sourceAsset, transcript, null);
    }

    public TranscriptCriticResult critique(CreatorShortVideo video, CreatorAsset sourceAsset, List<Map<String, Object>> transcript, Path sourceVideoPath) {
        String model = stringValue(properties.getAi().getGeminiModel(), "gemini-2.5-flash");
        List<Map<String, Object>> safeTranscript = transcript == null ? new ArrayList<>() : new ArrayList<>(transcript);
        if (sourceAsset == null || isBlank(sourceAsset.getBucket()) || isBlank(sourceAsset.getObjectKey())) {
            return skipped(model, "WARN", "Source video storage location is missing; deep transcript critic cannot verify audio.", safeTranscript);
        }
        UUID jobId = video == null ? null : video.getGenerationJobId();
        String transcriptFingerprint = transcriptFingerprint(safeTranscript);
        TranscriptCriticResult completedSourceCache = completedCriticFromSourceCache(video, sourceAsset, model, safeTranscript, transcriptFingerprint);
        if (completedSourceCache != null) {
            return completedSourceCache;
        }
        TranscriptCriticResult completedCheckpoint = completedCriticFromCheckpoint(jobId, sourceAsset, model, safeTranscript, transcriptFingerprint);
        if (completedCheckpoint != null) {
            return completedCheckpoint;
        }

        Path workspace = null;
        try {
            workspace = Files.createTempDirectory("creator-shorts-transcript-critic-");
            boolean reuseSource = reusableSourcePath(sourceVideoPath);
            Path sourcePath = reuseSource
                    ? sourceVideoPath
                    : workspace.resolve("source" + extensionFor(sourceAsset.getContentType(), sourceAsset.getObjectKey()));
            Path chunkDir = Files.createDirectories(workspace.resolve("audio-chunks"));

            if (!reuseSource) {
                assetStorageService.downloadObjectToPath(sourceAsset.getBucket(), sourceAsset.getObjectKey(), sourcePath);
            }
            extractAudioChunks(sourcePath, chunkDir);
            List<Path> chunks = audioChunks(chunkDir);
            if (chunks.isEmpty()) {
                return skipped(model, "WARN", "No audio chunks were produced from the uploaded source video; deep transcript critic could not inspect audio.", safeTranscript);
            }

            Map<Integer, ChunkAudit> checkpointedChunks = loadCriticSourceCheckpoint(video, sourceAsset, model, chunks.size(), transcriptFingerprint);
            checkpointedChunks.putAll(loadCriticCheckpoint(jobId, sourceAsset, model, chunks.size(), transcriptFingerprint));
            if (!checkpointedChunks.isEmpty()) {
                log.info(
                        "Deep shorts transcript critic resuming from durable chunk checkpoint jobId={} videoId={} completedChunks={} chunkCount={}",
                        jobId,
                        video == null ? null : video.getId(),
                        checkpointedChunks.size(),
                        chunks.size()
                );
            }

            List<Map<String, Object>> chunkAudits = new ArrayList<>();
            long inputTokens = 0;
            long outputTokens = 0;
            long totalTokens = 0;
            long providerTotalTokens = 0;
            for (int index = 0; index < chunks.size(); index++) {
                int chunkStart = index * CHUNK_SECONDS;
                int chunkEnd = chunkStart + CHUNK_SECONDS;
                ChunkAudit chunkAudit = checkpointedChunks.get(index);
                if (chunkAudit == null) {
                    chunkAudit = critiqueChunk(model, video, sourceAsset, chunks.get(index), index, chunkStart, chunkEnd, transcriptSlice(safeTranscript, chunkStart, chunkEnd));
                    checkpointedChunks.put(index, chunkAudit);
                    persistCriticCheckpoint(video, jobId, sourceAsset, model, chunks.size(), transcriptFingerprint, checkpointedChunks, false);
                }
                chunkAudits.add(chunkAudit.audit());
                inputTokens += longValue(chunkAudit.tokenMetadata().get("inputTokens"));
                outputTokens += longValue(chunkAudit.tokenMetadata().get("outputTokens"));
                totalTokens += longValue(chunkAudit.tokenMetadata().get("totalTokens"));
                providerTotalTokens += longValue(chunkAudit.tokenMetadata().get("providerTotalTokens"));
            }
            persistCriticCheckpoint(video, jobId, sourceAsset, model, chunks.size(), transcriptFingerprint, checkpointedChunks, true);

            Aggregate aggregate = aggregate(chunkAudits, safeTranscript, chunks.size());
            String usageSource = providerTotalTokens > 0 || inputTokens > 0 || outputTokens > 0 ? "PROVIDER" : "ESTIMATED_TEXT_ONLY";
            Map<String, Object> tokenMetadata = new LinkedHashMap<>();
            tokenMetadata.put("inputTokens", inputTokens);
            tokenMetadata.put("outputTokens", outputTokens);
            tokenMetadata.put("totalTokens", Math.max(totalTokens, inputTokens + outputTokens));
            tokenMetadata.put("providerTotalTokens", providerTotalTokens);
            tokenMetadata.put("source", usageSource);
            tokenMetadata.put("chunkSeconds", CHUNK_SECONDS);
            tokenMetadata.put("chunkCount", chunks.size());

            Map<String, Object> costMetadata = pricingService.estimateTextCall("gemini", model, PROMPT_TYPE, inputTokens, outputTokens, usageSource);
            costMetadata.put("provider", "gemini");
            costMetadata.put("model", model);
            costMetadata.put("operation", PROMPT_TYPE);
            costMetadata.put("googleGenaiBackend", googleGenAiClientFactory.backend());

            Map<String, Object> traceMetadata = new LinkedHashMap<>();
            traceMetadata.put("source", "gemini_audio_chunk_critic");
            traceMetadata.put("chunkCount", chunks.size());
            traceMetadata.put("transcriptNodeCount", safeTranscript.size());
            traceMetadata.put("passedChunks", aggregate.passedChunks());
            traceMetadata.put("warningChunks", aggregate.warningChunks());
            traceMetadata.put("failedChunks", aggregate.failedChunks());
            traceMetadata.put("coverageScore", aggregate.coverageScore());
            traceMetadata.put("timestampScore", aggregate.timestampScore());
            traceMetadata.put("speakerScore", aggregate.speakerScore());
            traceMetadata.put("hallucinationRisk", aggregate.hallucinationRisk());
            traceMetadata.put("issues", aggregate.issues());

            List<Map<String, Object>> trace = List.of(traceRow("TRANSCRIPT_CRITIC", aggregate.status(), aggregate.summary(), aggregate.confidence(), traceMetadata));

            Map<String, Object> metadata = new LinkedHashMap<>(traceMetadata);
            metadata.put("status", aggregate.status());
            metadata.put("summary", aggregate.summary());
            metadata.put("generatedAt", OffsetDateTime.now().toString());
            metadata.put("model", model);
            metadata.put("chunkSeconds", CHUNK_SECONDS);
            metadata.put("chunkAudits", chunkAudits);
            metadata.put("sourceAssetId", sourceAsset.getId() == null ? "" : sourceAsset.getId().toString());

            return new TranscriptCriticResult(true, "gemini", model, trace, chunkAudits, tokenMetadata, costMetadata, metadata);
        } catch (Exception ex) {
            log.warn("Deep shorts transcript critic failed videoId={} message={}", video == null ? null : video.getId(), ex.getMessage());
            return skipped(model, "WARN", "Deep transcript critic failed: " + safeMessage(ex), safeTranscript);
        } finally {
            deleteQuietly(workspace);
        }
    }

    private boolean reusableSourcePath(Path sourceVideoPath) {
        return sourceVideoPath != null && Files.isRegularFile(sourceVideoPath);
    }

    private Map<Integer, ChunkAudit> loadCriticCheckpoint(UUID jobId, CreatorAsset sourceAsset, String model, int chunkCount, String transcriptFingerprint) {
        Map<Integer, ChunkAudit> chunks = new java.util.TreeMap<>();
        if (jobId == null) {
            return chunks;
        }
        Map<String, Object> checkpoint = generationJobService.getGenerationJobCheckpoint(jobId, CHECKPOINT_KEY);
        if (!checkpointMatches(checkpoint, sourceAsset, model, chunkCount, transcriptFingerprint)) {
            return chunks;
        }
        return criticChunksFromCheckpoint(checkpoint, chunkCount);
    }

    private Map<Integer, ChunkAudit> loadCriticSourceCheckpoint(CreatorShortVideo video, CreatorAsset sourceAsset, String model, int chunkCount, String transcriptFingerprint) {
        Map<String, Object> checkpoint = sourceStageCacheService.findStageCache(video, sourceAsset, CHECKPOINT_KEY);
        if (!checkpointMatches(checkpoint, sourceAsset, model, chunkCount, transcriptFingerprint)) {
            return new java.util.TreeMap<>();
        }
        Map<Integer, ChunkAudit> chunks = criticChunksFromCheckpoint(checkpoint, chunkCount);
        if (!chunks.isEmpty()) {
            log.info(
                    "Deep shorts transcript critic restored source-level chunk cache videoId={} sourceCacheVideoId={} completedChunks={} chunkCount={}",
                    video == null ? null : video.getId(),
                    checkpoint.get("sourceCacheVideoId"),
                    chunks.size(),
                    chunkCount
            );
        }
        return chunks;
    }

    private Map<Integer, ChunkAudit> criticChunksFromCheckpoint(Map<String, Object> checkpoint, int chunkCount) {
        Map<Integer, ChunkAudit> chunks = new java.util.TreeMap<>();
        for (Map<String, Object> item : listOfMaps(checkpoint.get("chunks"))) {
            int chunkIndex = intValue(item.get("chunkIndex"), -1);
            if (chunkIndex < 0 || (chunkCount > 0 && chunkIndex >= chunkCount)) {
                continue;
            }
            Map<String, Object> audit = mapValue(item.get("audit"));
            Map<String, Object> tokenMetadata = mapValue(item.get("tokenMetadata"));
            if (audit.isEmpty()) {
                continue;
            }
            audit.put("checkpointRestored", true);
            chunks.put(chunkIndex, new ChunkAudit(audit, tokenMetadata));
        }
        return chunks;
    }

    private TranscriptCriticResult completedCriticFromCheckpoint(
            UUID jobId,
            CreatorAsset sourceAsset,
            String model,
            List<Map<String, Object>> transcript,
            String transcriptFingerprint
    ) {
        if (jobId == null) {
            return null;
        }
        Map<String, Object> checkpoint = generationJobService.getGenerationJobCheckpoint(jobId, CHECKPOINT_KEY);
        if (!"COMPLETED".equalsIgnoreCase(stringValue(checkpoint.get("status"), "")) || !checkpointMatches(checkpoint, sourceAsset, model, -1, transcriptFingerprint)) {
            return null;
        }
        int chunkCount = intValue(checkpoint.get("chunkCount"), 0);
        Map<Integer, ChunkAudit> chunks = criticChunksFromCheckpoint(checkpoint, chunkCount);
        if (chunkCount <= 0 || chunks.size() < chunkCount) {
            return null;
        }
        log.info(
                "Deep shorts transcript critic restored completed checkpoint jobId={} completedChunks={} chunkCount={}",
                jobId,
                chunks.size(),
                chunkCount
        );
        return criticResultFromChunks(model, sourceAsset, transcript, chunks, chunkCount, true);
    }

    private TranscriptCriticResult completedCriticFromSourceCache(
            CreatorShortVideo video,
            CreatorAsset sourceAsset,
            String model,
            List<Map<String, Object>> transcript,
            String transcriptFingerprint
    ) {
        Map<String, Object> checkpoint = sourceStageCacheService.findStageCache(video, sourceAsset, CHECKPOINT_KEY);
        if (!"COMPLETED".equalsIgnoreCase(stringValue(checkpoint.get("status"), "")) || !checkpointMatches(checkpoint, sourceAsset, model, -1, transcriptFingerprint)) {
            return null;
        }
        int chunkCount = intValue(checkpoint.get("chunkCount"), 0);
        Map<Integer, ChunkAudit> chunks = criticChunksFromCheckpoint(checkpoint, chunkCount);
        if (chunkCount <= 0 || chunks.size() < chunkCount) {
            return null;
        }
        log.info(
                "Deep shorts transcript critic restored completed source-level cache videoId={} sourceCacheVideoId={} completedChunks={} chunkCount={}",
                video == null ? null : video.getId(),
                checkpoint.get("sourceCacheVideoId"),
                chunks.size(),
                chunkCount
        );
        TranscriptCriticResult result = criticResultFromChunks(model, sourceAsset, transcript, chunks, chunkCount, true);
        Map<String, Object> metadata = new LinkedHashMap<>(result.metadata());
        metadata.put("restoredFromSourceCache", true);
        metadata.put("sourceCacheVideoId", stringValue(checkpoint.get("sourceCacheVideoId"), ""));
        Map<String, Object> tokenMetadata = new LinkedHashMap<>(result.tokenMetadata());
        tokenMetadata.put("restoredFromSourceCache", true);
        Map<String, Object> costMetadata = new LinkedHashMap<>(result.costMetadata());
        costMetadata.put("restoredFromSourceCache", true);
        return new TranscriptCriticResult(
                result.mediaBacked(),
                result.provider(),
                result.model(),
                result.trace(),
                result.chunkAudits(),
                tokenMetadata,
                costMetadata,
                metadata
        );
    }

    private TranscriptCriticResult criticResultFromChunks(
            String model,
            CreatorAsset sourceAsset,
            List<Map<String, Object>> transcript,
            Map<Integer, ChunkAudit> chunks,
            int chunkCount,
            boolean restoredFromCheckpoint
    ) {
        List<Map<String, Object>> chunkAudits = new ArrayList<>();
        long inputTokens = 0;
        long outputTokens = 0;
        long totalTokens = 0;
        long providerTotalTokens = 0;
        for (ChunkAudit audit : chunks.values()) {
            chunkAudits.add(audit.audit());
            inputTokens += longValue(audit.tokenMetadata().get("inputTokens"));
            outputTokens += longValue(audit.tokenMetadata().get("outputTokens"));
            totalTokens += longValue(audit.tokenMetadata().get("totalTokens"));
            providerTotalTokens += longValue(audit.tokenMetadata().get("providerTotalTokens"));
        }
        Aggregate aggregate = aggregate(chunkAudits, transcript, chunkCount);
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
        costMetadata.put("googleGenaiBackend", googleGenAiClientFactory.backend());

        Map<String, Object> traceMetadata = new LinkedHashMap<>();
        traceMetadata.put("source", "gemini_audio_chunk_critic");
        traceMetadata.put("chunkCount", chunkCount);
        traceMetadata.put("transcriptNodeCount", transcript == null ? 0 : transcript.size());
        traceMetadata.put("passedChunks", aggregate.passedChunks());
        traceMetadata.put("warningChunks", aggregate.warningChunks());
        traceMetadata.put("failedChunks", aggregate.failedChunks());
        traceMetadata.put("coverageScore", aggregate.coverageScore());
        traceMetadata.put("timestampScore", aggregate.timestampScore());
        traceMetadata.put("speakerScore", aggregate.speakerScore());
        traceMetadata.put("hallucinationRisk", aggregate.hallucinationRisk());
        traceMetadata.put("issues", aggregate.issues());
        traceMetadata.put("restoredFromCheckpoint", restoredFromCheckpoint);

        List<Map<String, Object>> trace = List.of(traceRow("TRANSCRIPT_CRITIC", aggregate.status(), restoredFromCheckpoint ? "Deep transcript critic restored completed chunk audits from checkpoint." : aggregate.summary(), aggregate.confidence(), traceMetadata));
        Map<String, Object> metadata = new LinkedHashMap<>(traceMetadata);
        metadata.put("status", aggregate.status());
        metadata.put("summary", aggregate.summary());
        metadata.put("generatedAt", OffsetDateTime.now().toString());
        metadata.put("model", model);
        metadata.put("chunkSeconds", CHUNK_SECONDS);
        metadata.put("chunkAudits", chunkAudits);
        metadata.put("sourceAssetId", sourceAsset == null || sourceAsset.getId() == null ? "" : sourceAsset.getId().toString());
        metadata.put("restoredFromCheckpoint", restoredFromCheckpoint);
        return new TranscriptCriticResult(true, "gemini", model, trace, chunkAudits, tokenMetadata, costMetadata, metadata);
    }

    private void persistCriticCheckpoint(
            CreatorShortVideo video,
            UUID jobId,
            CreatorAsset sourceAsset,
            String model,
            int chunkCount,
            String transcriptFingerprint,
            Map<Integer, ChunkAudit> chunks,
            boolean completed
    ) {
        try {
            Map<String, Object> checkpoint = new LinkedHashMap<>();
            checkpoint.put("source", "gemini_audio_chunk_critic");
            checkpoint.put("status", completed ? "COMPLETED" : "RUNNING");
            checkpoint.put("sourceAssetId", sourceAsset == null || sourceAsset.getId() == null ? "" : sourceAsset.getId().toString());
            checkpoint.put("model", model);
            checkpoint.put("chunkSeconds", CHUNK_SECONDS);
            checkpoint.put("chunkCount", chunkCount);
            checkpoint.put("completedChunkCount", chunks == null ? 0 : chunks.size());
            checkpoint.put("transcriptFingerprint", transcriptFingerprint);
            checkpoint.put("activeStage", "TRANSCRIPT_CRITIC");
            checkpoint.put("message", completed
                    ? "Deep transcript critic complete"
                    : "Auditing transcript chunk " + (chunks == null ? 0 : chunks.size()) + "/" + Math.max(1, chunkCount));
            checkpoint.put("jobProgress", 46 + (int) Math.floor((Math.min(chunks == null ? 0 : chunks.size(), Math.max(1, chunkCount)) * 2.0d) / Math.max(1, chunkCount)));
            checkpoint.put("updatedAt", OffsetDateTime.now().toString());
            List<Map<String, Object>> chunkPayload = new ArrayList<>();
            if (chunks != null) {
                for (Map.Entry<Integer, ChunkAudit> entry : chunks.entrySet()) {
                    ChunkAudit result = entry.getValue();
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("chunkIndex", entry.getKey());
                    item.put("audit", result == null ? Map.of() : new LinkedHashMap<>(result.audit()));
                    item.put("tokenMetadata", result == null ? Map.of() : new LinkedHashMap<>(result.tokenMetadata()));
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
            log.warn("Could not persist transcript critic chunk checkpoint jobId={} message={}", jobId, ex.getMessage());
        }
    }

    private boolean checkpointMatches(Map<String, Object> checkpoint, CreatorAsset sourceAsset, String model, int chunkCount, String transcriptFingerprint) {
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
        if (chunkCount > 0 && intValue(checkpoint.get("chunkCount"), chunkCount) != chunkCount) {
            return false;
        }
        return stringValue(checkpoint.get("transcriptFingerprint"), "").equals(transcriptFingerprint);
    }

    private String transcriptFingerprint(List<Map<String, Object>> transcript) {
        try {
            return Integer.toHexString(objectMapper.writeValueAsString(transcript == null ? List.of() : transcript).hashCode());
        } catch (JsonProcessingException ex) {
            return Integer.toHexString(String.valueOf(transcript == null ? List.of() : transcript).hashCode());
        }
    }

    private ChunkAudit critiqueChunk(String model, CreatorShortVideo video, CreatorAsset sourceAsset, Path chunkPath, int chunkIndex, int chunkStart, int chunkEnd, List<Map<String, Object>> transcriptSlice) throws IOException {
        byte[] audioBytes = Files.readAllBytes(chunkPath);
        String prompt = buildPrompt(video, sourceAsset, chunkIndex, chunkStart, chunkEnd, audioBytes.length, transcriptSlice);
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
        return normalizeChunkResponse(model, prompt, response == null ? Map.of() : response, chunkIndex, chunkStart, chunkEnd, audioBytes.length, transcriptSlice.size());
    }

    private Map<String, Object> buildRequest(String prompt, byte[] audioBytes) {
        List<Map<String, Object>> parts = new ArrayList<>();
        parts.add(Map.of("text", prompt));
        parts.add(Map.of("inline_data", Map.of("mime_type", "audio/wav", "data", Base64.getEncoder().encodeToString(audioBytes))));
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

    private String buildPrompt(CreatorShortVideo video, CreatorAsset sourceAsset, int chunkIndex, int chunkStart, int chunkEnd, int audioBytes, List<Map<String, Object>> transcriptSlice) {
        return """
                You are the independent TRANSCRIPT_CRITIC for a Generate Shorts backend pipeline.

                Verify the provided transcript nodes against the attached audio chunk. Do not trust the transcript just because another worker produced it. Listen for missing speech, hallucinated speech, timestamp drift, speaker mistakes, language/noise problems, and unusable sections.

                Chunk metadata:
                {
                  "shortVideoId": "%s",
                  "title": "%s",
                  "sourceAssetId": "%s",
                  "chunkIndex": %s,
                  "chunkStartSeconds": %s,
                  "chunkEndSeconds": %s,
                  "audioFormat": "wav 16khz mono",
                  "audioBytes": %s,
                  "providedTranscriptNodeCount": %s
                }

                Transcript nodes overlapping this chunk. Times are absolute source-video seconds:
                %s

                Return JSON only:
                {
                  "status": "PASS|WARN|FAIL",
                  "confidence": 0.0,
                  "coverageScore": 0,
                  "timestampScore": 0,
                  "speakerScore": 0,
                  "hallucinationRisk": "low|medium|high",
                  "missingSpeech": [],
                  "hallucinatedTranscript": [],
                  "timestampDriftIssues": [],
                  "speakerIssues": [],
                  "qualityIssues": [],
                  "suggestedRepairs": [{"nodeId":"n-001","issue":"short label","start":0.0,"end":2.0,"suggestedTranscript":"corrected or missing speech if concise"}],
                  "summary": "one sentence staff-level verdict for this chunk"
                }

                PASS only when coverage, timing, and hallucination risk are safe for edit decisions. WARN for repairable gaps or uncertainty. FAIL for mostly missing, fabricated, badly shifted, or unusable transcript. If no transcript nodes are provided, still listen to the audio and report whether speech exists.
                """.formatted(
                video == null || video.getId() == null ? "" : video.getId(),
                video == null ? "" : escape(video.getTitle()),
                sourceAsset == null || sourceAsset.getId() == null ? "" : sourceAsset.getId(),
                chunkIndex,
                chunkStart,
                chunkEnd,
                audioBytes,
                transcriptSlice.size(),
                compact(toJson(transcriptSlice), MAX_TRANSCRIPT_CHARS_PER_CHUNK)
        );
    }
    private ChunkAudit normalizeChunkResponse(String model, String prompt, Map<String, Object> response, int chunkIndex, int chunkStart, int chunkEnd, int audioBytes, int transcriptNodeCount) {
        String rawText = outputText(response);
        Map<String, Object> output = parseJsonObject(rawText);
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

        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("chunkIndex", chunkIndex);
        audit.put("chunkStartSeconds", chunkStart);
        audit.put("chunkEndSeconds", chunkEnd);
        audit.put("audioBytes", audioBytes);
        audit.put("transcriptNodeCount", transcriptNodeCount);
        audit.put("status", normalizeStatus(output.get("status")));
        audit.put("confidence", clamp(doubleValue(output.get("confidence"), 0.0), 0.0, 1.0));
        audit.put("coverageScore", clampInt(intValue(output.get("coverageScore"), 0), 0, 100));
        audit.put("timestampScore", clampInt(intValue(output.get("timestampScore"), 0), 0, 100));
        audit.put("speakerScore", clampInt(intValue(output.get("speakerScore"), 0), 0, 100));
        audit.put("hallucinationRisk", normalizeRisk(output.get("hallucinationRisk")));
        audit.put("missingSpeech", listValue(output.get("missingSpeech")));
        audit.put("hallucinatedTranscript", listValue(output.get("hallucinatedTranscript")));
        audit.put("timestampDriftIssues", listValue(output.get("timestampDriftIssues")));
        audit.put("speakerIssues", listValue(output.get("speakerIssues")));
        audit.put("qualityIssues", listValue(output.get("qualityIssues")));
        audit.put("suggestedRepairs", listValue(output.get("suggestedRepairs")));
        audit.put("summary", defaultString(output.get("summary"), "Transcript critic completed for chunk " + chunkIndex + "."));
        audit.put("model", model);
        audit.put("rawTextPreview", compact(rawText, 1600));
        return new ChunkAudit(audit, tokenMetadata);
    }

    private Aggregate aggregate(List<Map<String, Object>> audits, List<Map<String, Object>> transcript, int chunkCount) {
        int passed = 0;
        int warned = 0;
        int failed = 0;
        int coverageTotal = 0;
        int timestampTotal = 0;
        int speakerTotal = 0;
        double confidenceTotal = 0;
        int highRisk = 0;
        int mediumRisk = 0;
        List<String> issues = new ArrayList<>(deterministicIssues(transcript));

        for (Map<String, Object> audit : audits) {
            String status = normalizeStatus(audit.get("status"));
            if ("FAIL".equals(status)) failed++;
            else if ("WARN".equals(status)) warned++;
            else passed++;
            coverageTotal += intValue(audit.get("coverageScore"), 0);
            timestampTotal += intValue(audit.get("timestampScore"), 0);
            speakerTotal += intValue(audit.get("speakerScore"), 0);
            confidenceTotal += doubleValue(audit.get("confidence"), 0);
            String risk = normalizeRisk(audit.get("hallucinationRisk"));
            if ("high".equals(risk)) highRisk++;
            if ("medium".equals(risk)) mediumRisk++;
            addIssuePreview(issues, audit.get("missingSpeech"), "missing speech");
            addIssuePreview(issues, audit.get("hallucinatedTranscript"), "hallucination risk");
            addIssuePreview(issues, audit.get("timestampDriftIssues"), "timestamp drift");
            addIssuePreview(issues, audit.get("speakerIssues"), "speaker issue");
            addIssuePreview(issues, audit.get("qualityIssues"), "quality issue");
        }

        int denominator = Math.max(1, audits.size());
        int coverageScore = Math.round((float) coverageTotal / denominator);
        int timestampScore = Math.round((float) timestampTotal / denominator);
        int speakerScore = Math.round((float) speakerTotal / denominator);
        double confidence = clamp(confidenceTotal / denominator, 0.0, 1.0);
        String hallucinationRisk = highRisk > 0 ? "high" : (mediumRisk > 0 ? "medium" : "low");

        String status;
        if (transcript == null || transcript.isEmpty()) {
            status = failed + warned > 0 ? "FAIL" : "WARN";
        } else if (failed > Math.max(1, chunkCount / 4) || highRisk > 0) {
            status = "FAIL";
        } else if (failed > 0 || warned > 0 || coverageScore < 75 || timestampScore < 70 || !issues.isEmpty()) {
            status = "WARN";
        } else {
            status = "PASS";
        }

        String summary = switch (status) {
            case "PASS" -> "Deep transcript critic verified chunked audio against transcript nodes with usable coverage and timing.";
            case "FAIL" -> "Deep transcript critic found transcript quality problems severe enough to make edit decisions unsafe without repair.";
            default -> "Deep transcript critic found repairable transcript issues that downstream compression must treat cautiously.";
        };
        return new Aggregate(status, summary, confidence, coverageScore, timestampScore, speakerScore, hallucinationRisk, passed, warned, failed, limitStrings(issues, 20));
    }

    private List<String> deterministicIssues(List<Map<String, Object>> transcript) {
        List<String> issues = new ArrayList<>();
        if (transcript == null || transcript.isEmpty()) {
            issues.add("No transcript nodes were provided to the deep critic.");
            return issues;
        }
        double previousStart = -1;
        double previousEnd = -1;
        int emptyText = 0;
        int invalidTimes = 0;
        int largeGaps = 0;
        for (Map<String, Object> node : transcript) {
            double start = doubleValue(node.get("start"), -1);
            double end = doubleValue(node.get("end"), -1);
            if (stringValue(node.get("transcript"), "").trim().isBlank()) emptyText++;
            if (start < 0 || end <= start) invalidTimes++;
            if (previousStart >= 0 && start + 0.001 < previousStart) issues.add("Transcript timestamps are not monotonic around node " + defaultString(node.get("id"), "unknown") + ".");
            if (previousEnd >= 0 && start - previousEnd > 90) largeGaps++;
            previousStart = start;
            previousEnd = Math.max(previousEnd, end);
        }
        if (emptyText > 0) issues.add(emptyText + " transcript nodes have empty text.");
        if (invalidTimes > 0) issues.add(invalidTimes + " transcript nodes have invalid timestamps.");
        if (largeGaps > 0) issues.add(largeGaps + " large timestamp gaps need audio verification.");
        return issues;
    }

    private void addIssuePreview(List<String> issues, Object value, String label) {
        List<Object> items = listValue(value);
        if (!items.isEmpty()) {
            issues.add(label + ": " + compact(String.valueOf(items.get(0)), 180));
        }
    }

    private List<Map<String, Object>> transcriptSlice(List<Map<String, Object>> transcript, int chunkStart, int chunkEnd) {
        List<Map<String, Object>> slice = new ArrayList<>();
        if (transcript == null || transcript.isEmpty()) return slice;
        for (Map<String, Object> node : transcript) {
            double start = doubleValue(node.get("start"), -1);
            double end = doubleValue(node.get("end"), start + 1);
            if (end < chunkStart || start > chunkEnd) continue;
            Map<String, Object> copy = new LinkedHashMap<>(node);
            copy.put("chunkRelativeStart", round3(Math.max(0, start - chunkStart)));
            copy.put("chunkRelativeEnd", round3(Math.max(0, end - chunkStart)));
            slice.add(copy);
        }
        return slice;
    }
    private void extractAudioChunks(Path sourcePath, Path chunkDir) {
        run(List.of(
                "ffmpeg", "-hide_banner", "-y", "-i", sourcePath.toString(), "-vn", "-ac", "1", "-ar", "16000",
                "-f", "segment", "-segment_time", String.valueOf(CHUNK_SECONDS), "-reset_timestamps", "1", "-c:a", "pcm_s16le",
                chunkDir.resolve("chunk-%05d.wav").toString()
        ), AUDIO_COMMAND_TIMEOUT, "ffmpeg audio chunking for transcript critic");
    }

    private List<Path> audioChunks(Path chunkDir) throws IOException {
        try (var stream = Files.list(chunkDir)) {
            return stream.filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".wav")).sorted().toList();
        }
    }

    private String run(List<String> command, Duration timeout, String label) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            boolean finished = process.waitFor(timeout.toSeconds(), TimeUnit.SECONDS);
            String output = new String(process.getInputStream().readAllBytes());
            if (!finished) {
                process.destroyForcibly();
                throw new IllegalStateException(label + " timed out.");
            }
            if (process.exitValue() != 0) throw new IllegalStateException(label + " failed: " + tail(output, 3000));
            return output;
        } catch (IOException ex) {
            throw new IllegalStateException("ffmpeg is not available for " + label + ".", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(label + " was interrupted.", ex);
        }
    }

    private TranscriptCriticResult skipped(String model, String status, String reason, List<Map<String, Object>> transcript) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "gemini_audio_chunk_critic");
        metadata.put("status", status);
        metadata.put("reason", reason);
        metadata.put("generatedAt", OffsetDateTime.now().toString());
        metadata.put("transcriptNodeCount", transcript == null ? 0 : transcript.size());

        List<Map<String, Object>> trace = List.of(traceRow("TRANSCRIPT_CRITIC", status, reason, "FAIL".equals(status) ? 0.0 : 0.2, metadata));
        Map<String, Object> tokenMetadata = new LinkedHashMap<>();
        tokenMetadata.put("inputTokens", 0);
        tokenMetadata.put("outputTokens", 0);
        tokenMetadata.put("totalTokens", 0);
        tokenMetadata.put("source", "NONE");
        Map<String, Object> costMetadata = pricingService.estimateTextCall("gemini", model, PROMPT_TYPE, 0, 0, "NONE");
        costMetadata.put("provider", "gemini");
        costMetadata.put("model", model);
        costMetadata.put("operation", PROMPT_TYPE);
        return new TranscriptCriticResult(false, "gemini", model, trace, List.of(), tokenMetadata, costMetadata, metadata);
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

    private List<Map<String, Object>> listOfMaps(Object value) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                Map<String, Object> map = mapValue(item);
                if (!map.isEmpty()) {
                    result.add(map);
                }
            }
        }
        return result;
    }

    private List<Object> listValue(Object value) {
        if (value instanceof List<?> list) return new ArrayList<>(list);
        if (value == null || String.valueOf(value).isBlank()) return new ArrayList<>();
        return new ArrayList<>(List.of(String.valueOf(value)));
    }

    private List<String> limitStrings(List<String> values, int limit) {
        List<String> result = new ArrayList<>();
        for (String value : values == null ? List.<String>of() : values) {
            if (value == null || value.isBlank()) continue;
            result.add(compact(value, 220));
            if (result.size() >= limit) break;
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

    private String normalizeRisk(Object value) {
        String risk = stringValue(value, "medium").trim().toLowerCase(Locale.ROOT);
        return switch (risk) {
            case "low", "medium", "high" -> risk;
            default -> "medium";
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

    private record ChunkAudit(Map<String, Object> audit, Map<String, Object> tokenMetadata) {
    }

    private record Aggregate(
            String status,
            String summary,
            double confidence,
            int coverageScore,
            int timestampScore,
            int speakerScore,
            String hallucinationRisk,
            int passedChunks,
            int warningChunks,
            int failedChunks,
            List<String> issues
    ) {
    }

    public record TranscriptCriticResult(
            boolean mediaBacked,
            String provider,
            String model,
            List<Map<String, Object>> trace,
            List<Map<String, Object>> chunkAudits,
            Map<String, Object> tokenMetadata,
            Map<String, Object> costMetadata,
            Map<String, Object> metadata
    ) {
    }
}
