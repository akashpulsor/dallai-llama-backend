package com.dalai.llama.creator.service;

import com.dalai.llama.creator.domain.entity.CreatorAsset;
import com.dalai.llama.creator.domain.entity.CreatorGenerationJob;
import com.dalai.llama.creator.domain.entity.CreatorShortCandidate;
import com.dalai.llama.creator.domain.entity.CreatorShortVideo;
import com.dalai.llama.creator.dto.request.CompleteShortMultipartUploadRequest;
import com.dalai.llama.creator.dto.request.GenerateShortsRequest;
import com.dalai.llama.creator.dto.request.CompleteShortUploadRequest;
import com.dalai.llama.creator.dto.request.ShortUploadSessionRequest;
import com.dalai.llama.creator.dto.request.ShortUploadPartUrlRequest;
import com.dalai.llama.creator.dto.request.ShortCandidateReviewRequest;
import com.dalai.llama.creator.dto.response.ShortCandidateResponse;
import com.dalai.llama.creator.dto.response.ShortGenerationResponse;
import com.dalai.llama.creator.dto.response.ShortMultipartUploadSessionResponse;
import com.dalai.llama.creator.dto.response.ShortUploadSessionResponse;
import com.dalai.llama.creator.dto.response.ShortUploadPartUrlResponse;
import com.dalai.llama.creator.repository.CreatorAssetRepository;
import com.dalai.llama.creator.repository.CreatorShortCandidateRepository;
import com.dalai.llama.creator.repository.CreatorShortVideoRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.hibernate.exception.JDBCConnectionException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.OutputStream;
import java.sql.SQLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class CreatorShortGenerationService {

    private static final Logger log = LoggerFactory.getLogger(CreatorShortGenerationService.class);
    private static final AtomicInteger SCENE_VISUAL_PREP_THREAD_COUNTER = new AtomicInteger(1);
    private static final ThreadFactory SCENE_VISUAL_PREP_THREAD_FACTORY = runnable -> {
        Thread thread = new Thread(runnable, "creator-scene-visual-prep-" + SCENE_VISUAL_PREP_THREAD_COUNTER.getAndIncrement());
        thread.setDaemon(true);
        return thread;
    };
    private static final Duration SIGNED_URL_TTL = Duration.ofDays(7);
    private static final long DEFAULT_CHUNK_SIZE_BYTES = 8L * 1024L * 1024L;
    private static final long MAX_CHUNK_SIZE_BYTES = 64L * 1024L * 1024L;
    private static final long DEFAULT_DIRECT_UPLOAD_PART_SIZE_BYTES = 64L * 1024L * 1024L;
    private static final long MIN_DIRECT_UPLOAD_PART_SIZE_BYTES = 5L * 1024L * 1024L;
    private static final long MAX_DIRECT_UPLOAD_SIZE_BYTES = 10L * 1024L * 1024L * 1024L;
    private static final Duration DIRECT_UPLOAD_PART_URL_TTL = Duration.ofMinutes(30);
    private static final int MAX_CHUNK_COUNT = 10000;
    private static final int MAX_PLANNER_TRANSCRIPT_NODES = 240;
    private static final int MAX_PLANNER_SCENES = 180;
    private static final int MAX_PLANNER_GRAPH_NODES = 260;
    private static final int MAX_PLANNER_GRAPH_EDGES = 360;
    private static final int MAX_JOB_OUTPUT_TRANSCRIPT_PREVIEW_NODES = 80;
    private static final int MAX_JOB_OUTPUT_GRAPH_PREVIEW_NODES = 80;
    private static final int MAX_JOB_OUTPUT_GRAPH_PREVIEW_EDGES = 120;
    private static final double FAST_LANE_MAX_DURATION_SECONDS = 20 * 60.0;
    private static final double TRANSCRIPT_QA_TARGET_WINDOW_SECONDS = 60.0;
    private static final double TRANSCRIPT_QA_MIN_WINDOW_SECONDS = 12.0;
    private static final double TRANSCRIPT_QA_MAX_WINDOW_SECONDS = 90.0;
    private static final String JOB_TYPE = "SHORTS_GENERATE";
    private static final String JOB_TYPE_VISUAL_ANALYSIS = "SHORTS_VISUAL_ANALYSIS";
    private static final String JOB_TYPE_RERENDER = "SHORTS_CANDIDATE_RERENDER";
    private static final String ASSET_TYPE_SOURCE_VIDEO = "SHORT_SOURCE_VIDEO";
    private static final Set<String> VIDEO_EXTENSIONS = Set.of("mp4", "mov", "m4v", "webm", "mkv", "avi");
    private static final List<String> PIPELINE_STAGES = List.of(
            "INGESTION",
            "TRANSCRIPT",
            "TRANSCRIPT_CRITIC",
            "VIDEO_TYPE_CLASSIFICATION",
            "VIDEO_TYPE_CRITIC",
            "VIDEO_ANALYSIS",
            "CONVERSATION_STRUCTURE",
            "SCENE_ANALYSIS",
            "SCENE_CRITIC",
            "VIDEO_GRAPH_BUILDER",
            "STORY_UNDERSTANDING",
            "STORY_CRITIC",
            "INTERESTINGNESS",
            "INTERESTINGNESS_CRITIC",
            "STORY_BEAT_PLANNING",
            "VISUAL_STORY_COMPOSITION",
            "COMPRESSION",
            "COMPRESSION_CRITIC",
            "HOOK_GENERATION",
            "HOOK_CRITIC",
            "CAPTION_PLANNING",
            "CAPTION_CRITIC",
            "VISUAL_ENHANCEMENT",
            "VISUAL_CRITIC",
            "CONTINUITY_CRITIC",
            "CANDIDATE_RANKING",
            "GLOBAL_CRITIC",
            "TARGETED_REPAIR",
            "RENDERING",
            "POST_RENDER_QA",
            "COMPLETED"
    );
    private static final List<String> MANUAL_STEP_STAGES = List.of(
            "INGESTION",
            "VIDEO_TYPE_CLASSIFICATION",
            "TRANSCRIPT",
            "TRANSCRIPT_CRITIC",
            "VIDEO_TYPE_CRITIC",
            "VIDEO_ANALYSIS",
            "SCENE_ANALYSIS",
            "SCENE_REPAIR",
            "SCENE_CRITIC",
            "VIDEO_GRAPH_BUILDER",
            "STORY_CRITIC",
            "INTERESTINGNESS",
            "INTERESTINGNESS_CRITIC",
            "STORY_UNDERSTANDING",
            "STORY_BEAT_PLANNING",
            "VISUAL_STORY_COMPOSITION",
            "COMPRESSION",
            "HOOK_GENERATION",
            "TARGETED_REPAIR",
            "VISUAL_ENHANCEMENT",
            "VISUAL_CRITIC",
            "CONTINUITY_CRITIC",
            "CANDIDATE_RANKING",
            "GLOBAL_CRITIC",
            "RENDERING",
            "POST_RENDER_QA"
    );

    private final CreatorShortVideoRepository videoRepository;
    private final CreatorShortCandidateRepository candidateRepository;
    private final CreatorAssetRepository assetRepository;
    private final AssetStorageService assetStorageService;
    private final GenerationJobService generationJobService;
    private final CreatorAiService creatorAiService;
    private final GoogleShortVideoUnderstandingService videoUnderstandingService;
    private final GoogleShortVideoTypeCriticService videoTypeCriticService;
    private final GoogleShortFullTranscriptWorkerService fullTranscriptWorkerService;
    private final GoogleShortTranscriptCriticService transcriptCriticService;
    private final ShortSceneAnalysisService sceneAnalysisService;
    private final GoogleShortSceneMapService geminiSceneMapService;
    private final GoogleShortSceneCriticService sceneCriticService;
    private final ShortVideoGraphBuilderService graphBuilderService;
    private final GoogleShortStoryCriticService storyCriticService;
    private final ShortInterestingnessScoringService interestingnessScoringService;
    private final ShortInterestingnessCriticService interestingnessCriticService;
    private final ShortStoryBeatPlanningService storyBeatPlanningService;
    private final ShortVisualStoryCompositionService visualStoryCompositionService;
    private final ShortCompressionPlannerService compressionPlannerService;
    private final ShortHookGenerationService hookGenerationService;
    private final ShortCandidateCriticRepairService candidateCriticRepairService;
    private final ShortVisualEnhancementService visualEnhancementService;
    private final ShortVisualCriticService visualCriticService;
    private final ShortContinuityCriticService continuityCriticService;
    private final ShortCandidateRankingService candidateRankingService;
    private final ShortGlobalCriticService globalCriticService;
    private final ShortRenderingService shortRenderingService;
    private final ShortPostRenderQaService postRenderQaService;
    private final ObjectMapper objectMapper;
    private final ExecutorService sceneVisualPrepExecutor = Executors.newFixedThreadPool(2, SCENE_VISUAL_PREP_THREAD_FACTORY);

    public CreatorShortGenerationService(
            CreatorShortVideoRepository videoRepository,
            CreatorShortCandidateRepository candidateRepository,
            CreatorAssetRepository assetRepository,
            AssetStorageService assetStorageService,
            GenerationJobService generationJobService,
            CreatorAiService creatorAiService,
            GoogleShortVideoUnderstandingService videoUnderstandingService,
            GoogleShortVideoTypeCriticService videoTypeCriticService,
            GoogleShortFullTranscriptWorkerService fullTranscriptWorkerService,
            GoogleShortTranscriptCriticService transcriptCriticService,
            ShortSceneAnalysisService sceneAnalysisService,
            GoogleShortSceneMapService geminiSceneMapService,
            GoogleShortSceneCriticService sceneCriticService,
            ShortVideoGraphBuilderService graphBuilderService,
            GoogleShortStoryCriticService storyCriticService,
            ShortInterestingnessScoringService interestingnessScoringService,
            ShortInterestingnessCriticService interestingnessCriticService,
            ShortStoryBeatPlanningService storyBeatPlanningService,
            ShortVisualStoryCompositionService visualStoryCompositionService,
            ShortCompressionPlannerService compressionPlannerService,
            ShortHookGenerationService hookGenerationService,
            ShortCandidateCriticRepairService candidateCriticRepairService,
            ShortVisualEnhancementService visualEnhancementService,
            ShortVisualCriticService visualCriticService,
            ShortContinuityCriticService continuityCriticService,
            ShortCandidateRankingService candidateRankingService,
            ShortGlobalCriticService globalCriticService,
            ShortRenderingService shortRenderingService,
            ShortPostRenderQaService postRenderQaService,
            ObjectMapper objectMapper
    ) {
        this.videoRepository = videoRepository;
        this.candidateRepository = candidateRepository;
        this.assetRepository = assetRepository;
        this.assetStorageService = assetStorageService;
        this.generationJobService = generationJobService;
        this.creatorAiService = creatorAiService;
        this.videoUnderstandingService = videoUnderstandingService;
        this.videoTypeCriticService = videoTypeCriticService;
        this.fullTranscriptWorkerService = fullTranscriptWorkerService;
        this.transcriptCriticService = transcriptCriticService;
        this.sceneAnalysisService = sceneAnalysisService;
        this.geminiSceneMapService = geminiSceneMapService;
        this.sceneCriticService = sceneCriticService;
        this.graphBuilderService = graphBuilderService;
        this.storyCriticService = storyCriticService;
        this.interestingnessScoringService = interestingnessScoringService;
        this.interestingnessCriticService = interestingnessCriticService;
        this.storyBeatPlanningService = storyBeatPlanningService;
        this.visualStoryCompositionService = visualStoryCompositionService;
        this.compressionPlannerService = compressionPlannerService;
        this.hookGenerationService = hookGenerationService;
        this.candidateCriticRepairService = candidateCriticRepairService;
        this.visualEnhancementService = visualEnhancementService;
        this.visualCriticService = visualCriticService;
        this.continuityCriticService = continuityCriticService;
        this.candidateRankingService = candidateRankingService;
        this.globalCriticService = globalCriticService;
        this.shortRenderingService = shortRenderingService;
        this.postRenderQaService = postRenderQaService;
        this.objectMapper = objectMapper;
    }

    @PreDestroy
    public void shutdownSceneVisualPrepExecutor() {
        sceneVisualPrepExecutor.shutdownNow();
    }

    public ShortGenerationResponse startGeneration(MultipartFile file, GenerateShortsRequest request, String tenantId, String userId) {
        String safeTenantId = safeTenantId(tenantId);
        String safeUserId = safeUserId(userId);
        GenerateShortsRequest safeRequest = safeGenerateRequest(request);

        validateUpload(file);
        assertWalletBalance(safeRequest, safeTenantId, safeUserId);
        log.info(
                "Generate Shorts upload accepted tenantId={} userId={} filename={} contentType={} sizeBytes={} requestedShorts={} targetDurationSeconds={} platform={} reviewMode={}",
                safeTenantId,
                safeUserId,
                file.getOriginalFilename(),
                file.getContentType(),
                file.getSize(),
                safeRequest.requestedShorts(),
                safeRequest.targetDurationSeconds(),
                safeRequest.platform(),
                safeRequest.reviewMode()
        );

        UUID videoId = UUID.randomUUID();
        String contentType = defaultString(file.getContentType(), "application/octet-stream");
        String originalFilename = defaultString(file.getOriginalFilename(), "source-video.mp4");
        String objectKey = sourceObjectKey(safeTenantId, videoId, originalFilename);

        AssetStorageService.StoredObject stored;
        Path uploadPath = null;
        try {
            uploadPath = Files.createTempFile("creator-short-upload-" + videoId + "-", "." + defaultString(extension(originalFilename), "mp4"));
            file.transferTo(uploadPath);
            stored = assetStorageService.uploadCreatorAssetFromPath(objectKey, uploadPath, contentType, SIGNED_URL_TTL);
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read uploaded source video.", ex);
        } finally {
            if (uploadPath != null) {
                try {
                    Files.deleteIfExists(uploadPath);
                } catch (IOException ex) {
                    log.warn("Could not delete temporary short upload file path={}", uploadPath, ex);
                }
            }
        }
        log.info(
                "Generate Shorts source upload stored videoId={} tenantId={} userId={} bucket={} objectKey={} contentType={} sizeBytes={}",
                videoId,
                safeTenantId,
                safeUserId,
                stored.bucket(),
                stored.objectKey(),
                stored.contentType(),
                stored.sizeBytes()
        );

        return startGenerationFromStoredSource(
                videoId,
                stored,
                originalFilename,
                safeRequest,
                safeTenantId,
                safeUserId,
                "generate_shorts_upload",
                new LinkedHashMap<>()
        );
    }

    public ShortUploadSessionResponse createChunkUpload(ShortUploadSessionRequest request, String tenantId, String userId) {
        ShortUploadSessionRequest safeRequest = request == null
                ? new ShortUploadSessionRequest(null, null, null, null, null)
                : request;
        validateVideoMetadata(
                defaultString(safeRequest.originalFilename(), "source-video.mp4"),
                defaultString(safeRequest.contentType(), "application/octet-stream")
        );
        long chunkSize = normalizeChunkSize(safeRequest.chunkSizeBytes());
        int totalChunks = normalizeTotalChunks(safeRequest.totalChunks(), safeRequest.sizeBytes(), chunkSize);
        return new ShortUploadSessionResponse(
                UUID.randomUUID(),
                "READY",
                null,
                totalChunks,
                chunkSize,
                MAX_CHUNK_SIZE_BYTES,
                0L,
                List.of(),
                "Chunk upload session created. Upload each part, then complete to merge and queue generation."
        );
    }

    public ShortMultipartUploadSessionResponse createMultipartUpload(ShortUploadSessionRequest request, String tenantId, String userId) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Upload metadata is required.");
        }
        String safeTenantId = safeTenantId(tenantId);
        String safeUserId = safeUserId(userId);
        String originalFilename = defaultString(request.originalFilename(), "source-video.mp4");
        String contentType = defaultString(request.contentType(), "application/octet-stream");
        validateVideoMetadata(originalFilename, contentType);
        long sizeBytes = normalizeDirectUploadSize(request.sizeBytes());
        long partSizeBytes = normalizeDirectUploadPartSize(request.chunkSizeBytes());
        int totalParts = normalizeDirectUploadPartCount(request.totalChunks(), sizeBytes, partSizeBytes);
        UUID uploadId = UUID.randomUUID();
        UUID videoId = UUID.randomUUID();
        String objectKey = directUploadObjectKey(safeTenantId, safeUserId, uploadId, videoId, originalFilename);
        AssetStorageService.MultipartUpload multipartUpload = assetStorageService.createCreatorMultipartUpload(objectKey, contentType);
        log.info(
                "Generate Shorts direct multipart upload created uploadId={} videoId={} tenantId={} userId={} bucket={} objectKey={} storageUploadId={} contentType={} sizeBytes={} totalParts={} partSizeBytes={}",
                uploadId,
                videoId,
                safeTenantId,
                safeUserId,
                multipartUpload.bucket(),
                multipartUpload.objectKey(),
                multipartUpload.uploadId(),
                contentType,
                sizeBytes,
                totalParts,
                partSizeBytes
        );
        return new ShortMultipartUploadSessionResponse(
                uploadId,
                videoId,
                "READY",
                multipartUpload.bucket(),
                multipartUpload.objectKey(),
                multipartUpload.uploadId(),
                totalParts,
                partSizeBytes,
                MAX_CHUNK_SIZE_BYTES,
                sizeBytes,
                Math.toIntExact(DIRECT_UPLOAD_PART_URL_TTL.toSeconds()),
                "Direct multipart upload session created. Upload parts directly to object storage, then complete to queue ingestion."
        );
    }

    public ShortUploadPartUrlResponse presignMultipartUploadPart(
            UUID uploadId,
            int partNumber,
            ShortUploadPartUrlRequest request,
            String tenantId,
            String userId
    ) {
        validateUploadId(uploadId);
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Upload part metadata is required.");
        }
        UUID videoId = request.videoId();
        if (videoId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Video id is required for direct upload.");
        }
        String storageUploadId = validateStorageUploadId(request.storageUploadId());
        String safeTenantId = safeTenantId(tenantId);
        String safeUserId = safeUserId(userId);
        String originalFilename = defaultString(request.originalFilename(), "source-video.mp4");
        String contentType = defaultString(request.contentType(), "application/octet-stream");
        validateVideoMetadata(originalFilename, contentType);
        long sizeBytes = normalizeDirectUploadSize(request.sizeBytes());
        long partSizeBytes = normalizeDirectUploadPartSize(request.partSizeBytes());
        int totalParts = normalizeDirectUploadPartCount(request.totalParts(), sizeBytes, partSizeBytes);
        validateDirectPartNumber(partNumber, totalParts);
        String objectKey = directUploadObjectKey(safeTenantId, safeUserId, uploadId, videoId, originalFilename);
        String uploadUrl = assetStorageService.presignedCreatorUploadPartUrl(
                objectKey,
                storageUploadId,
                partNumber,
                DIRECT_UPLOAD_PART_URL_TTL
        );
        long startByte = Math.max(0L, (long) (partNumber - 1) * partSizeBytes);
        long endByte = Math.min(sizeBytes - 1, startByte + partSizeBytes - 1);
        return new ShortUploadPartUrlResponse(
                uploadId,
                videoId,
                partNumber,
                "PUT",
                uploadUrl,
                Math.toIntExact(DIRECT_UPLOAD_PART_URL_TTL.toSeconds()),
                startByte,
                endByte,
                "Upload part " + partNumber + "/" + totalParts + " directly to object storage."
        );
    }

    public ShortGenerationResponse completeMultipartUpload(
            UUID uploadId,
            CompleteShortMultipartUploadRequest request,
            String tenantId,
            String userId
    ) {
        validateUploadId(uploadId);
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Upload completion metadata is required.");
        }
        UUID videoId = request.videoId();
        if (videoId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Video id is required for direct upload completion.");
        }
        String storageUploadId = validateStorageUploadId(request.storageUploadId());
        GenerateShortsRequest safeRequest = safeGenerateRequest(request.toGenerateShortsRequest());
        String safeTenantId = safeTenantId(tenantId);
        String safeUserId = safeUserId(userId);
        String originalFilename = defaultString(request.originalFilename(), "source-video.mp4");
        String contentType = defaultString(request.contentType(), "application/octet-stream");
        validateVideoMetadata(originalFilename, contentType);
        long sizeBytes = normalizeDirectUploadSize(request.sizeBytes());
        long partSizeBytes = normalizeDirectUploadPartSize(request.partSizeBytes());
        int totalParts = normalizeDirectUploadPartCount(request.totalParts(), sizeBytes, partSizeBytes);
        assertWalletBalance(safeRequest, safeTenantId, safeUserId);

        String objectKey = directUploadObjectKey(safeTenantId, safeUserId, uploadId, videoId, originalFilename);
        AssetStorageService.StoredObject stored;
        try {
            stored = assetStorageService.completeCreatorMultipartUpload(
                    objectKey,
                    storageUploadId,
                    totalParts,
                    contentType,
                    sizeBytes,
                    SIGNED_URL_TTL
            );
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
        log.info(
                "Generate Shorts direct multipart upload completed uploadId={} videoId={} tenantId={} userId={} bucket={} objectKey={} contentType={} sizeBytes={} totalParts={}",
                uploadId,
                videoId,
                safeTenantId,
                safeUserId,
                stored.bucket(),
                stored.objectKey(),
                stored.contentType(),
                stored.sizeBytes(),
                totalParts
        );
        Map<String, Object> uploadMetadata = new LinkedHashMap<>();
        uploadMetadata.put("directMultipartUpload", true);
        uploadMetadata.put("uploadId", uploadId.toString());
        uploadMetadata.put("storageUploadId", storageUploadId);
        uploadMetadata.put("totalParts", totalParts);
        uploadMetadata.put("partSizeBytes", partSizeBytes);
        uploadMetadata.put("declaredSizeBytes", sizeBytes);
        uploadMetadata.put("completedSizeBytes", stored.sizeBytes());
        return startGenerationFromStoredSource(
                videoId,
                stored,
                originalFilename,
                safeRequest,
                safeTenantId,
                safeUserId,
                "generate_shorts_direct_multipart_upload",
                uploadMetadata
        );
    }

    public ShortUploadSessionResponse uploadChunk(
            UUID uploadId,
            int chunkIndex,
            MultipartFile chunk,
            Integer totalChunks,
            Long sizeBytes,
            String tenantId,
            String userId
    ) {
        validateUploadId(uploadId);
        int safeTotalChunks = normalizeTotalChunks(totalChunks, sizeBytes, DEFAULT_CHUNK_SIZE_BYTES);
        validateChunkIndex(chunkIndex, safeTotalChunks);
        if (chunk == null || chunk.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Chunk payload is required.");
        }
        if (chunk.getSize() > MAX_CHUNK_SIZE_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Chunk is too large. Use chunks of 64 MB or less.");
        }
        Path chunkPath = null;
        try {
            String objectKey = chunkObjectKey(safeTenantId(tenantId), safeUserId(userId), uploadId, chunkIndex);
            chunkPath = Files.createTempFile("creator-short-chunk-" + uploadId + "-" + chunkIndex + "-", ".part");
            chunk.transferTo(chunkPath);
            assetStorageService.putCreatorObjectFromPath(objectKey, chunkPath, "application/octet-stream");
            return new ShortUploadSessionResponse(
                    uploadId,
                    "PART_UPLOADED",
                    chunkIndex,
                    safeTotalChunks,
                    chunk.getSize(),
                    MAX_CHUNK_SIZE_BYTES,
                    chunk.getSize(),
                    List.of(),
                "Chunk " + (chunkIndex + 1) + "/" + safeTotalChunks + " uploaded."
            );
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read uploaded chunk.", ex);
        } finally {
            if (chunkPath != null) {
                try {
                    Files.deleteIfExists(chunkPath);
                } catch (IOException ex) {
                    log.warn("Could not delete temporary short chunk file path={}", chunkPath, ex);
                }
            }
        }
    }

    public ShortGenerationResponse completeChunkUpload(
            UUID uploadId,
            CompleteShortUploadRequest request,
            String tenantId,
            String userId
    ) {
        validateUploadId(uploadId);
        CompleteShortUploadRequest safeCompleteRequest = request == null
                ? new CompleteShortUploadRequest(null, null, null, null, null, null, null, null, null, null, null, null, null)
                : request;
        GenerateShortsRequest safeRequest = safeGenerateRequest(safeCompleteRequest.toGenerateShortsRequest());
        String safeTenantId = safeTenantId(tenantId);
        String safeUserId = safeUserId(userId);
        String originalFilename = defaultString(safeCompleteRequest.originalFilename(), "source-video.mp4");
        String contentType = defaultString(safeCompleteRequest.contentType(), "application/octet-stream");
        validateVideoMetadata(originalFilename, contentType);
        int totalChunks = normalizeTotalChunks(safeCompleteRequest.totalChunks(), safeCompleteRequest.sizeBytes(), DEFAULT_CHUNK_SIZE_BYTES);
        assertWalletBalance(safeRequest, safeTenantId, safeUserId);

        UUID videoId = UUID.randomUUID();
        Path mergedPath = null;
        try {
            mergedPath = Files.createTempFile("creator-short-source-" + uploadId + "-", "." + defaultString(extension(originalFilename), "mp4"));
            long mergedSizeBytes = mergeChunkUploadToPath(safeTenantId, safeUserId, uploadId, totalChunks, mergedPath);
            Long declaredSize = safeCompleteRequest.sizeBytes();
            if (declaredSize != null && declaredSize > 0 && mergedSizeBytes != declaredSize) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Merged upload size mismatch. Expected " + declaredSize + " bytes but received " + mergedSizeBytes + "."
                );
            }
            AssetStorageService.StoredObject stored = assetStorageService.uploadCreatorAssetFromPath(
                    sourceObjectKey(safeTenantId, videoId, originalFilename),
                    mergedPath,
                    contentType,
                    SIGNED_URL_TTL
            );
            log.info(
                    "Generate Shorts chunk upload merged and stored uploadId={} videoId={} tenantId={} userId={} bucket={} objectKey={} contentType={} sizeBytes={} totalChunks={}",
                    uploadId,
                    videoId,
                    safeTenantId,
                    safeUserId,
                    stored.bucket(),
                    stored.objectKey(),
                    stored.contentType(),
                    stored.sizeBytes(),
                    totalChunks
            );
            Map<String, Object> uploadMetadata = new LinkedHashMap<>();
            uploadMetadata.put("chunkedUpload", true);
            uploadMetadata.put("uploadId", uploadId.toString());
            uploadMetadata.put("totalChunks", totalChunks);
            uploadMetadata.put("declaredSizeBytes", declaredSize == null ? 0L : declaredSize);
            uploadMetadata.put("mergedSizeBytes", mergedSizeBytes);
            ShortGenerationResponse response = startGenerationFromStoredSource(
                    videoId,
                    stored,
                    originalFilename,
                    safeRequest,
                    safeTenantId,
                    safeUserId,
                    "generate_shorts_chunk_upload",
                    uploadMetadata
            );
            deleteChunksBestEffort(safeTenantId, safeUserId, uploadId, totalChunks);
            return response;
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not merge uploaded chunks.", ex);
        } finally {
            if (mergedPath != null) {
                try {
                    Files.deleteIfExists(mergedPath);
                } catch (IOException ex) {
                    log.warn("Could not delete temporary merged short upload file path={}", mergedPath, ex);
                }
            }
        }
    }

    private ShortGenerationResponse startGenerationFromStoredSource(
            UUID videoId,
            AssetStorageService.StoredObject stored,
            String originalFilename,
            GenerateShortsRequest safeRequest,
            String safeTenantId,
            String safeUserId,
            String sourceLabel,
            Map<String, Object> ingestionMetadata
    ) {
        Map<String, Object> settings = buildSettings(safeRequest);
        Map<String, Object> safeIngestionMetadata = new LinkedHashMap<>(ingestionMetadata == null ? Map.of() : ingestionMetadata);
        String contentType = defaultString(stored.contentType(), "application/octet-stream");

        Map<String, Object> sourceMetadata = new LinkedHashMap<>();
        sourceMetadata.putAll(safeIngestionMetadata);
        sourceMetadata.put("shortVideoId", videoId.toString());
        sourceMetadata.put("source", sourceLabel);
        sourceMetadata.put("originalFilename", originalFilename);
        sourceMetadata.put("platform", settings.get("platform"));
        sourceMetadata.put("targetDurationSeconds", settings.get("targetDurationSeconds"));
        sourceMetadata.put("requestedShorts", settings.get("requestedShorts"));
        sourceMetadata.put("reviewMode", settings.get("reviewMode"));
        sourceMetadata.put("contentType", contentType);
        sourceMetadata.put("sizeBytes", stored.sizeBytes());

        CreatorAsset sourceAsset = assetRepository.saveAndFlush(CreatorAsset.builder()
                .tenantId(safeTenantId)
                .userId(safeUserId)
                .projectId(safeRequest.projectId())
                .assetType(ASSET_TYPE_SOURCE_VIDEO)
                .bucket(stored.bucket())
                .objectKey(stored.objectKey())
                .contentType(contentType)
                .sizeBytes(stored.sizeBytes())
                .publicUrl(stored.signedUrl())
                .metadata(sourceMetadata)
                .build());

        Map<String, Object> jobInput = new LinkedHashMap<>();
        jobInput.put("shortVideoId", videoId.toString());
        jobInput.put("sourceAssetId", sourceAsset.getId().toString());
        jobInput.put("settings", settings);
        jobInput.put("pipeline", PIPELINE_STAGES);

        CreatorGenerationJob job = generationJobService.startGenerationJob(
                JOB_TYPE,
                safeTenantId,
                safeUserId,
                safeRequest.projectId(),
                jobInput
        );

        CreatorShortVideo video = CreatorShortVideo.builder()
                .id(videoId)
                .tenantId(safeTenantId)
                .userId(safeUserId)
                .projectId(safeRequest.projectId())
                .sourceAssetId(sourceAsset.getId())
                .generationJobId(job.getId())
                .title(defaultString(safeRequest.title(), stripExtension(originalFilename)))
                .originalFileName(originalFilename)
                .platform(stringValue(settings.get("platform")))
                .targetDurationSeconds(intValue(settings.get("targetDurationSeconds"), 60))
                .requestedShorts(intValue(settings.get("requestedShorts"), 20))
                .reviewMode(stringValue(settings.get("reviewMode")))
                .status("QUEUED")
                .settings(settings)
                .metadata(initialVideoMetadata(sourceAsset, stored, safeRequest, sourceLabel, safeIngestionMetadata))
                .build();
        video = videoRepository.saveAndFlush(video);
        log.info(
                "Generate Shorts video record created videoId={} jobId={} sourceAssetId={} tenantId={} userId={} status={} requestedShorts={} targetDurationSeconds={} platform={}",
                video.getId(),
                job.getId(),
                sourceAsset.getId(),
                safeTenantId,
                safeUserId,
                video.getStatus(),
                video.getRequestedShorts(),
                video.getTargetDurationSeconds(),
                video.getPlatform()
        );

        Map<String, Object> queuePayload = shortsQueuePayload(video, sourceAsset, safeRequest);
        generationJobService.queueExistingGenerationJob(job.getId(), null, "Generate Shorts job queued", Map.of(
                "shortVideoId", video.getId().toString(),
                "sourceAssetId", sourceAsset.getId().toString(),
                "status", "QUEUED",
                "trace", List.of(Map.of(
                        "stage", "INGESTION",
                        "status", "COMPLETED",
                        "summary", "Source video stored in MinIO and queued for Generate Shorts worker.",
                        "confidence", 1.0,
                        "timestamp", OffsetDateTime.now().toString()
                ))
        ));
        publishExistingGenerationJobAfterCommit(null, job.getId(), queuePayload);
        log.info(
                "Generate Shorts Kafka publish requested jobId={} videoId={} sourceAssetId={} tenantId={} userId={} topic={}",
                job.getId(),
                video.getId(),
                sourceAsset.getId(),
                safeTenantId,
                safeUserId,
                "creator.generation.jobs"
        );

        Map<String, Object> metadata = mutableMap(video.getMetadata());
        metadata.put("queuedAt", OffsetDateTime.now().toString());
        metadata.put("queue", Map.of(
                "topic", "creator.generation.jobs",
                "jobType", JOB_TYPE,
                "sourceAssetId", sourceAsset.getId().toString()
        ));
        video.setMetadata(metadata);
        video = videoRepository.saveAndFlush(video);
        return toResponse(video);
    }

    private long mergeChunkUploadToPath(String tenantId, String userId, UUID uploadId, int totalChunks, Path mergedPath) throws IOException {
        List<Integer> missing = missingChunks(tenantId, userId, uploadId, totalChunks);
        if (!missing.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Upload is missing chunks: " + missing.stream().limit(20).toList());
        }
        long bytes = 0L;
        try (OutputStream outputStream = Files.newOutputStream(mergedPath)) {
            for (int index = 0; index < totalChunks; index++) {
                String objectKey = chunkObjectKey(tenantId, userId, uploadId, index);
                long before = Files.size(mergedPath);
                assetStorageService.downloadCreatorObjectToOutputStream(objectKey, outputStream);
                outputStream.flush();
                bytes += Math.max(0L, Files.size(mergedPath) - before);
            }
        }
        return bytes;
    }

    private List<Integer> missingChunks(String tenantId, String userId, UUID uploadId, int totalChunks) {
        List<Integer> missing = new ArrayList<>();
        for (int index = 0; index < totalChunks; index++) {
            if (!assetStorageService.creatorObjectExists(chunkObjectKey(tenantId, userId, uploadId, index))) {
                missing.add(index);
            }
        }
        return missing;
    }

    private void deleteChunksBestEffort(String tenantId, String userId, UUID uploadId, int totalChunks) {
        for (int index = 0; index < totalChunks; index++) {
            String objectKey = chunkObjectKey(tenantId, userId, uploadId, index);
            try {
                assetStorageService.deleteCreatorObject(objectKey);
            } catch (RuntimeException ex) {
                log.warn("Could not delete uploaded short source chunk objectKey={}", objectKey, ex);
            }
        }
    }

    public void runQueuedGenerationJob(UUID jobId, UUID videoId, UUID sourceAssetId, GenerateShortsRequest request) {
        CreatorShortVideo video = videoRepository.findById(videoId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Queued short video was not found."));
        CreatorAsset sourceAsset = assetRepository.findById(sourceAssetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Queued source video asset was not found."));
        if (jobId != null && !jobId.equals(video.getGenerationJobId())) {
            log.warn(
                    "Generate Shorts worker correcting stale video job pointer queuedJobId={} persistedJobId={} videoId={} sourceAssetId={}",
                    jobId,
                    video.getGenerationJobId(),
                    videoId,
                    sourceAssetId
            );
            video.setGenerationJobId(jobId);
            video = videoRepository.saveAndFlush(video);
        }
        GenerateShortsRequest safeRequest = request == null
                ? new GenerateShortsRequest(video.getProjectId(), video.getTitle(), video.getPlatform(), video.getTargetDurationSeconds(), video.getRequestedShorts(), video.getReviewMode(), null, stringValue(mutableMap(video.getMetadata()).get("notes")), stringValue(mutableMap(video.getSettings()).get("executionMode")))
                : request;
        Path sourceWorkspace = null;
        try {
            log.info(
                    "Generate Shorts worker starting jobId={} videoId={} sourceAssetId={} tenantId={} userId={} requestedShorts={} targetDurationSeconds={} platform={}",
                    jobId,
                    videoId,
                    sourceAssetId,
                    video.getTenantId(),
                    video.getUserId(),
                    safeRequest.requestedShorts(),
                    safeRequest.targetDurationSeconds(),
                    safeRequest.platform()
            );
            video.setStatus("RUNNING");
            videoRepository.saveAndFlush(video);
            sourceWorkspace = Files.createTempDirectory("creator-shorts-source-");
            Path sourceVideoPath = sourceWorkspace.resolve("source" + extensionFor(sourceAsset.getContentType(), sourceAsset.getObjectKey(), ".mp4"));
            assetStorageService.downloadObjectToPath(sourceAsset.getBucket(), sourceAsset.getObjectKey(), sourceVideoPath);
            long sourceSizeBytes = Files.size(sourceVideoPath);
            log.info(
                    "Generate Shorts source downloaded jobId={} videoId={} sourceAssetId={} contentType={} bytes={}",
                    jobId,
                    videoId,
                    sourceAssetId,
                    defaultString(sourceAsset.getContentType(), "application/octet-stream"),
                    sourceSizeBytes
            );
            runPlanningPipeline(video, sourceAsset, safeRequest, sourceVideoPath, sourceSizeBytes, defaultString(sourceAsset.getContentType(), "application/octet-stream"));
        } catch (ResponseStatusException ex) {
            log.warn(
                    "Generate Shorts worker failed with response status jobId={} videoId={} status={} reason={}",
                    jobId,
                    videoId,
                    ex.getStatusCode(),
                    ex.getReason()
            );
            failVideo(video.getId(), jobId, ex.getReason());
            throw ex;
        } catch (Exception ex) {
            if (isTransientDatabaseFailure(ex)) {
                log.warn(
                        "Generate Shorts worker hit transient database connection failure jobId={} videoId={} errorType={} errorMessage={}. Leaving job for Kafka retry or stale-job recovery.",
                        jobId,
                        videoId,
                        ex.getClass().getSimpleName(),
                        ex.getMessage()
                );
                throw ex instanceof RuntimeException runtime ? runtime : new IllegalStateException("Transient database failure during shorts generation.", ex);
            }
            log.error(
                    "Generate Shorts worker failed jobId={} videoId={} errorType={} errorMessage={}",
                    jobId,
                    videoId,
                    ex.getClass().getSimpleName(),
                    ex.getMessage(),
                    ex
            );
            failVideo(video.getId(), jobId, "Shorts generation failed.");
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Shorts generation failed.", ex);
        } finally {
            deleteQuietly(sourceWorkspace);
        }
    }

    public void runQueuedVisualAnalysisJob(UUID jobId, UUID videoId, UUID sourceAssetId) {
        CreatorShortVideo video = videoRepository.findById(videoId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Queued short video was not found."));
        CreatorAsset sourceAsset = assetRepository.findById(sourceAssetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Queued source video asset was not found."));
        Path sourceWorkspace = null;
        try {
            log.info(
                    "Optional visual analysis worker starting jobId={} videoId={} sourceAssetId={} tenantId={} userId={}",
                    jobId,
                    videoId,
                    sourceAssetId,
                    video.getTenantId(),
                    video.getUserId()
            );
            generationJobService.updateGenerationJobProgress(jobId, 12, "Visual analysis downloading source video", Map.of(
                    "activeStage", "VISUAL_ANALYSIS",
                    "shortVideoId", videoId.toString(),
                    "sourceAssetId", sourceAssetId.toString()
            ));
            sourceWorkspace = Files.createTempDirectory("creator-shorts-visual-analysis-");
            Path sourceVideoPath = sourceWorkspace.resolve("source" + extensionFor(sourceAsset.getContentType(), sourceAsset.getObjectKey(), ".mp4"));
            assetStorageService.downloadObjectToPath(sourceAsset.getBucket(), sourceAsset.getObjectKey(), sourceVideoPath);
            long sourceSizeBytes = Files.size(sourceVideoPath);
            generationJobService.updateGenerationJobProgress(jobId, 28, "Visual analysis asking Gemini for timestamped scene map", Map.of(
                    "activeStage", "VISUAL_ANALYSIS",
                    "sourceSizeBytes", sourceSizeBytes,
                    "sceneMapSource", "gemini_video_scene_map"
            ));

            List<Map<String, Object>> transcript = listOfMaps(mutableMap(video.getTranscriptPayload()).get("nodes"));
            ShortsPipelineStrategy optionalStrategy = frameFreeVideoAnalysisStrategy(video, "OPTIONAL_VISUAL_ANALYSIS", sourceSizeBytes, transcript.size());
            GoogleShortSceneMapService.SceneMapResult geminiSceneMap = geminiSceneMapService.mapScenes(video, sourceAsset, transcript, sourceVideoPath);
            ShortSceneAnalysisService.SceneAnalysisResult sceneAnalysis;
            if (geminiSceneMap.mediaBacked() && !geminiSceneMap.visualAnalysis().scenes().isEmpty()) {
                sceneAnalysis = sceneAnalysisService.attachTranscript(video, geminiSceneMap.visualAnalysis(), transcript);
                if (!restoredFromSourceCache(geminiSceneMap.metadata(), geminiSceneMap.tokenMetadata(), geminiSceneMap.costMetadata())) {
                    creatorAiService.publishProviderUsageDebit(
                            "SHORTS_GEMINI_SCENE_MAP",
                            geminiSceneMap.provider(),
                            geminiSceneMap.model(),
                            geminiSceneMap.costMetadata(),
                            new CreatorAiService.AiUsageContext(video.getTenantId(), video.getUserId(), video.getProjectId(), jobId, null),
                            "Gemini timestamped scene map for optional shorts visual analysis"
                    );
                }
            } else {
                log.warn(
                        "Gemini scene map unavailable for optional visual analysis; using transcript windows without FFmpeg frame extraction jobId={} videoId={} reason={}",
                        jobId,
                        videoId,
                        defaultString(geminiSceneMap.metadata().get("reason"), "no usable Gemini scenes")
                );
                sceneAnalysis = transcriptFirstSceneAnalysis(video, transcript, optionalStrategy);
            }
            Map<String, Object> sceneAnalysisMetadata = mutableMap(sceneAnalysis.metadata());
            sceneAnalysisMetadata.put("source", "optional_visual_analysis");
            sceneAnalysisMetadata.put("mode", "OPTIONAL_VISUAL_ANALYSIS");
            sceneAnalysisMetadata.put("primarySceneMapSource", geminiSceneMap.mediaBacked() ? "gemini_video_scene_map" : "transcript_scene_windows");
            sceneAnalysisMetadata.put("geminiSceneMap", geminiSceneMap.metadata());
            sceneAnalysisMetadata.put("sourceSizeBytes", sourceSizeBytes);
            sceneAnalysisMetadata.put("jobId", jobId == null ? "" : jobId.toString());
            sceneAnalysisMetadata.put("frameExtractionSkipped", true);
            sceneAnalysisMetadata.put("pipelineStrategy", optionalStrategy.metadata());
            sceneAnalysis = new ShortSceneAnalysisService.SceneAnalysisResult(
                    sceneAnalysis.mediaBacked(),
                    sceneAnalysis.scenes(),
                    sceneAnalysis.frames(),
                    sceneAnalysis.transcript(),
                    sceneAnalysis.trace(),
                    sceneAnalysisMetadata
            );
            List<Map<String, Object>> visualTrace = new ArrayList<>(withoutTraceStage(sceneAnalysis.trace(), "SCENE_CRITIC"));
            generationJobService.updateGenerationJobProgress(jobId, 62, "Visual analysis auditing scene continuity", Map.of(
                    "activeStage", "VISUAL_ANALYSIS",
                    "sceneCount", sceneAnalysis.scenes().size(),
                    "frameCount", sceneAnalysis.frames().size(),
                    "sceneAnalysis", Map.of(
                            "mediaBacked", sceneAnalysis.mediaBacked(),
                            "metadata", sceneAnalysis.metadata(),
                            "scenes", sceneAnalysis.scenes(),
                            "frames", sceneAnalysis.frames()
                    ),
                    "trace", visualTrace
            ));

            Map<String, Object> mediaVideoDna = mutableMap(video.getVideoDna());
            if (mediaVideoDna.isEmpty()) {
                mediaVideoDna = fallbackVideoDna(video);
            }
            GoogleShortSceneCriticService.SceneCriticResult sceneCritic = isFullVideoSceneMapAnalysis(sceneAnalysis)
                    ? sceneMapBackedSceneCritic(video, sceneAnalysis, optionalStrategy)
                    : transcriptFirstSceneCritic(video, sceneAnalysis, optionalStrategy);
            visualTrace.addAll(sceneCritic.trace());
            SceneEvidenceRepair sceneEvidenceRepair = repairSceneEvidenceIfUnsafe(video, sceneAnalysis, sceneCritic, transcript);
            if (sceneEvidenceRepair.applied()) {
                sceneAnalysis = sceneEvidenceRepair.sceneAnalysis();
                sceneCritic = withSceneEvidenceRepair(sceneCritic, sceneEvidenceRepair);
                addTrace(
                        visualTrace,
                        "VISUAL_ANALYSIS_REPAIR",
                        "WARN",
                        "Visual analysis found unsafe scene evidence, so conservative transcript-led windows were generated.",
                        76,
                        sceneEvidenceRepair.metadata()
                );
            }
            if (sceneCritic.mediaBacked()
                    && !isSceneMapBackedSceneCritic(sceneCritic)
                    && !restoredFromSourceCache(sceneCritic.metadata(), sceneCritic.tokenMetadata(), sceneCritic.costMetadata())) {
                creatorAiService.publishProviderUsageDebit(
                        "SHORTS_SCENE_CRITIC",
                        sceneCritic.provider(),
                        sceneCritic.model(),
                        sceneCritic.costMetadata(),
                        new CreatorAiService.AiUsageContext(video.getTenantId(), video.getUserId(), video.getProjectId(), jobId, null),
                        "Optional visual analysis scene critic for shorts"
                );
            }

            Map<String, Object> sceneTimeline = sceneTimeline(
                    sceneAnalysis.transcript(),
                    sceneAnalysis.scenes(),
                    sceneAnalysis.frames(),
                    sceneCritic.sceneCritic(),
                    Map.of()
            );
            Map<String, Object> visualAnalysisState = new LinkedHashMap<>();
            visualAnalysisState.put("status", "COMPLETED");
            visualAnalysisState.put("source", "optional_visual_analysis");
            visualAnalysisState.put("jobId", jobId == null ? "" : jobId.toString());
            visualAnalysisState.put("completedAt", OffsetDateTime.now().toString());
            visualAnalysisState.put("sceneCount", sceneAnalysis.scenes().size());
            visualAnalysisState.put("frameCount", sceneAnalysis.frames().size());
            visualAnalysisState.put("transcriptNodeCount", sceneAnalysis.transcript().size());
            visualAnalysisState.put("message", "Visual analysis complete. Scene and frame evidence can now support cutting and story hooks.");

            Map<String, Object> metadata = mutableMap(video.getMetadata());
            metadata.put("visualAnalysis", visualAnalysisState);
            metadata.put("visualAnalysisJobId", jobId == null ? "" : jobId.toString());
            metadata.put("sceneAnalysis", Map.of(
                    "mediaBacked", sceneAnalysis.mediaBacked(),
                    "metadata", sceneAnalysis.metadata(),
                    "sceneCount", sceneAnalysis.scenes().size(),
                    "frameCount", sceneAnalysis.frames().size(),
                    "scenes", sceneAnalysis.scenes(),
                    "frames", sceneAnalysis.frames()
            ));
            metadata.put("deepSceneCritic", Map.of(
                    "mediaBacked", sceneCritic.mediaBacked(),
                    "provider", sceneCritic.provider(),
                    "model", sceneCritic.model(),
                    "sceneCritic", sceneCritic.sceneCritic(),
                    "evidenceMetadata", sceneCritic.evidenceMetadata(),
                    "tokenMetadata", sceneCritic.tokenMetadata(),
                    "costMetadata", sceneCritic.costMetadata(),
                    "metadata", sceneCritic.metadata()
            ));
            metadata.put("sceneTimeline", sceneTimeline);

            List<Map<String, Object>> trace = listOfMaps(mutableMap(video.getTracePayload()).get("stages"));
            trace.addAll(visualTrace);
            Map<String, Object> tracePayload = new LinkedHashMap<>();
            tracePayload.put("stages", trace);
            tracePayload.put("pipeline", PIPELINE_STAGES);
            tracePayload.put("visualAnalysisJobId", jobId == null ? "" : jobId.toString());

            video.setMetadata(metadata);
            video.setTracePayload(tracePayload);
            video.setUpdatedAt(OffsetDateTime.now());
            CreatorShortVideo saved = saveShortVideoWithTransientRetry(video, "optional_visual_analysis_persistence", jobId);

            Map<String, Object> output = new LinkedHashMap<>();
            output.put("shortVideoId", saved.getId().toString());
            output.put("sourceAssetId", sourceAsset.getId().toString());
            output.put("activeStage", "VISUAL_ANALYSIS");
            output.put("visualAnalysis", visualAnalysisState);
            output.put("sceneCount", sceneAnalysis.scenes().size());
            output.put("frameCount", sceneAnalysis.frames().size());
            output.put("transcriptNodeCount", sceneAnalysis.transcript().size());
            output.put("sceneAnalysis", Map.of(
                    "mediaBacked", sceneAnalysis.mediaBacked(),
                    "metadata", sceneAnalysis.metadata(),
                    "sceneCount", sceneAnalysis.scenes().size(),
                    "frameCount", sceneAnalysis.frames().size()
            ));
            output.put("sceneCritic", sceneCritic.sceneCritic());
            output.put("trace", visualTrace);
            generationJobService.completeGenerationJob(jobId, output);
            log.info(
                    "Optional visual analysis completed jobId={} videoId={} sceneCount={} frameCount={}",
                    jobId,
                    videoId,
                    sceneAnalysis.scenes().size(),
                    sceneAnalysis.frames().size()
            );
        } catch (RuntimeException ex) {
            if (isTransientDatabaseFailure(ex)) {
                log.warn(
                        "Optional visual analysis hit transient database failure jobId={} videoId={} errorType={} errorMessage={}. Leaving job for retry or stale-job recovery.",
                        jobId,
                        videoId,
                        ex.getClass().getSimpleName(),
                        ex.getMessage()
                );
                throw ex;
            }
            markVisualAnalysisFailed(videoId, jobId, ex.getMessage());
            generationJobService.failGenerationJob(jobId, defaultString(ex.getMessage(), "Visual analysis failed."));
            log.error("Optional visual analysis failed jobId={} videoId={} errorType={} errorMessage={}", jobId, videoId, ex.getClass().getSimpleName(), ex.getMessage(), ex);
            throw ex;
        } catch (Exception ex) {
            markVisualAnalysisFailed(videoId, jobId, ex.getMessage());
            generationJobService.failGenerationJob(jobId, defaultString(ex.getMessage(), "Visual analysis failed."));
            log.error("Optional visual analysis failed jobId={} videoId={} errorType={} errorMessage={}", jobId, videoId, ex.getClass().getSimpleName(), ex.getMessage(), ex);
            throw new IllegalStateException("Visual analysis failed.", ex);
        } finally {
            deleteQuietly(sourceWorkspace);
        }
    }

    @Transactional(readOnly = true)
    public ShortGenerationResponse getVideo(UUID videoId, String tenantId, String userId) {
        CreatorShortVideo video = loadVideo(videoId, tenantId, userId);
        return toResponse(video);
    }

    @Transactional(readOnly = true)
    public List<ShortGenerationResponse> listVideos(String tenantId, String userId) {
        return videoRepository.findTop20ByTenantIdAndUserIdOrderByCreatedAtDesc(safeTenantId(tenantId), safeUserId(userId))
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ShortCandidateResponse> listCandidates(UUID videoId, String tenantId, String userId) {
        CreatorShortVideo video = loadVideo(videoId, tenantId, userId);
        return candidateRepository.findByVideoIdOrderByRankIndexAsc(video.getId())
                .stream()
                .map(this::toCandidateResponse)
                .toList();
    }

    @Transactional
    public ShortGenerationResponse requestVisualAnalysis(UUID videoId, Map<String, Object> payload, String tenantId, String userId) {
        CreatorShortVideo video = loadVideo(videoId, tenantId, userId);
        if (video.getSourceAssetId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This short video cannot run visual analysis because the source asset is missing.");
        }
        CreatorAsset sourceAsset = assetRepository.findById(video.getSourceAssetId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Queued source video asset was not found."));
        Map<String, Object> metadata = mutableMap(video.getMetadata());
        Map<String, Object> existingVisualAnalysis = mapValue(metadata.get("visualAnalysis"));
        UUID existingJobId = uuidValue(existingVisualAnalysis.get("jobId"));
        if (existingJobId != null) {
            CreatorGenerationJob existingJob = generationJobService.findGenerationJob(existingJobId).orElse(null);
            if (existingJob != null && !isTerminalShortJobStatus(existingJob.getStatus())) {
                return toResponse(video);
            }
        }

        Map<String, Object> requestPayload = mapValue(payload);
        Map<String, Object> visualState = new LinkedHashMap<>();
        visualState.put("status", "QUEUED");
        visualState.put("source", "optional_visual_analysis");
        visualState.put("message", "Visual analysis queued. This can take several minutes on long videos.");
        visualState.put("requestedAt", OffsetDateTime.now().toString());
        visualState.put("requestedBy", safeUserId(userId));
        visualState.put("reason", defaultString(requestPayload.get("reason"), "user_requested_optional_visual_analysis"));
        visualState.put("shortVideoId", video.getId().toString());
        visualState.put("sourceAssetId", sourceAsset.getId().toString());

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("jobType", JOB_TYPE_VISUAL_ANALYSIS);
        input.put("shortVideoId", video.getId().toString());
        input.put("sourceAssetId", sourceAsset.getId().toString());
        input.put("sourceGenerationJobId", video.getGenerationJobId() == null ? null : video.getGenerationJobId().toString());
        input.put("settings", mutableMap(video.getSettings()));
        input.put("request", Map.of(
                "mode", "OPTIONAL_VISUAL_ANALYSIS",
                "reason", visualState.get("reason"),
                "runsSceneCritic", true
        ));
        input.put("visualAnalysis", visualState);

        CreatorGenerationJob job = generationJobService.startGenerationJob(
                JOB_TYPE_VISUAL_ANALYSIS,
                video.getTenantId(),
                video.getUserId(),
                video.getProjectId(),
                input
        );
        visualState.put("jobId", job.getId().toString());

        generationJobService.queueExistingGenerationJob(job.getId(), null, "Visual analysis queued", Map.of(
                "shortVideoId", video.getId().toString(),
                "sourceAssetId", sourceAsset.getId().toString(),
                "activeStage", "VISUAL_ANALYSIS",
                "visualAnalysis", visualState,
                "trace", List.of(Map.of(
                        "stage", "VISUAL_ANALYSIS",
                        "status", "QUEUED",
                        "summary", "Optional visual analysis queued. This can take several minutes.",
                        "confidence", 1.0,
                        "timestamp", OffsetDateTime.now().toString(),
                        "metadata", visualState
                ))
        ));

        metadata.put("visualAnalysis", visualState);
        metadata.put("visualAnalysisJobId", job.getId().toString());
        video.setMetadata(metadata);
        CreatorShortVideo saved = videoRepository.saveAndFlush(video);

        Map<String, Object> kafkaPayload = new LinkedHashMap<>();
        kafkaPayload.put("jobType", JOB_TYPE_VISUAL_ANALYSIS);
        kafkaPayload.put("jobId", job.getId().toString());
        kafkaPayload.put("shortVideoId", video.getId().toString());
        kafkaPayload.put("sourceAssetId", sourceAsset.getId().toString());
        kafkaPayload.put("queuedAt", OffsetDateTime.now().toString());
        kafkaPayload.put("visualAnalysis", visualState);
        publishExistingGenerationJobAfterCommit(null, job.getId(), kafkaPayload);
        log.info(
                "Optional visual analysis queued jobId={} videoId={} sourceAssetId={} tenantId={} userId={}",
                job.getId(),
                video.getId(),
                sourceAsset.getId(),
                video.getTenantId(),
                video.getUserId()
        );
        return toResponse(saved);
    }

    @Transactional
    public ShortGenerationResponse pauseGeneration(UUID videoId, Map<String, Object> payload, String tenantId, String userId) {
        CreatorShortVideo video = loadVideo(videoId, tenantId, userId);
        if (video.getGenerationJobId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This short video does not have a generation job to pause.");
        }
        Map<String, Object> requestPayload = mapValue(payload);
        String reason = defaultString(requestPayload.get("reason"), "user_requested_pause");
        CreatorGenerationJob job = generationJobService.requestPauseGenerationJob(video.getGenerationJobId(), tenantId, userId, reason);

        Map<String, Object> metadata = mutableMap(video.getMetadata());
        Map<String, Object> pauseState = new LinkedHashMap<>();
        pauseState.put("requested", true);
        pauseState.put("reason", reason);
        pauseState.put("requestedAt", OffsetDateTime.now().toString());
        pauseState.put("jobStatus", job.getStatus());
        metadata.put("pauseState", pauseState);
        if ("PAUSED".equalsIgnoreCase(job.getStatus())) {
            video.setStatus("PAUSED");
            pauseState.put("pausedAt", OffsetDateTime.now().toString());
            pauseState.put("message", "Generation paused before worker pickup.");
        }
        video.setMetadata(metadata);
        CreatorShortVideo saved = videoRepository.saveAndFlush(video);
        log.info(
                "Generate Shorts pause requested jobId={} videoId={} tenantId={} userId={} jobStatus={} reason={}",
                video.getGenerationJobId(),
                video.getId(),
                video.getTenantId(),
                video.getUserId(),
                job.getStatus(),
                reason
        );
        return toResponse(saved);
    }

    @Transactional
    public ShortGenerationResponse saveProcessingTimeline(UUID videoId, Map<String, Object> payload, String tenantId, String userId) {
        CreatorShortVideo video = loadVideo(videoId, tenantId, userId);
        if (video.getGenerationJobId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This short video does not have a generation job.");
        }
        CreatorGenerationJob job = generationJobService.findGenerationJob(video.getGenerationJobId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Generation job was not found."));
        if (!"PAUSED".equalsIgnoreCase(job.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Pause generation before editing the processing timeline.");
        }

        Map<String, Object> requestPayload = mapValue(payload);
        Map<String, Object> jobOutput = mutableMap(job.getOutputPayload());
        List<Map<String, Object>> submittedTranscript = listOfMaps(firstNonEmpty(
                requestPayload.get("transcript"),
                requestPayload.get("nodes"),
                requestPayload.get("timeline")
        ));
        if (submittedTranscript.isEmpty()) {
            submittedTranscript = listOfMaps(firstNonEmpty(
                    jobOutput.get("editedTranscript"),
                    jobOutput.get("transcript"),
                    mutableMap(video.getTranscriptPayload()).get("nodes")
            ));
        }
        TranscriptRepair repair = repairTranscriptTimeline(submittedTranscript);
        List<Map<String, Object>> editedTranscript = repair.transcript();
        if (editedTranscript.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Timeline edits must include at least one transcript dialogue node.");
        }

        Map<String, Object> submittedGraph = mapValue(firstNonEmpty(requestPayload.get("graph"), requestPayload.get("transcriptGraph")));
        Map<String, Object> editedGraph = submittedGraph.isEmpty()
                ? transcriptTimelineGraph(editedTranscript, "human_processing_timeline_edit")
                : submittedGraph;
        Map<String, Object> graphMetadata = mutableMap(mapValue(editedGraph.get("metadata")));
        graphMetadata.put("humanEdited", true);
        graphMetadata.put("humanEditedAt", OffsetDateTime.now().toString());
        graphMetadata.put("source", defaultString(graphMetadata.get("source"), "human_processing_timeline_edit"));
        editedGraph.put("metadata", graphMetadata);

        Map<String, Object> storyEdits = mapValue(firstNonEmpty(requestPayload.get("storyEdits"), requestPayload.get("story")));
        List<Map<String, Object>> storyNodes = listOfMaps(firstNonEmpty(requestPayload.get("storyNodes"), requestPayload.get("stories")));
        Map<String, Object> humanProcessingEdits = new LinkedHashMap<>();
        humanProcessingEdits.put("source", "human_processing_timeline_editor");
        humanProcessingEdits.put("editedAt", OffsetDateTime.now().toString());
        humanProcessingEdits.put("editedBy", safeUserId(userId));
        humanProcessingEdits.put("transcriptNodeCount", editedTranscript.size());
        humanProcessingEdits.put("graphNodeCount", listOfMaps(editedGraph.get("nodes")).size());
        humanProcessingEdits.put("graphEdgeCount", listOfMaps(editedGraph.get("edges")).size());
        humanProcessingEdits.put("transcriptRepair", repair.metadata());
        if (!storyEdits.isEmpty()) {
            humanProcessingEdits.put("storyEdits", storyEdits);
        }
        if (!storyNodes.isEmpty()) {
            humanProcessingEdits.put("storyNodes", storyNodes);
        }

        Map<String, Object> transcriptPayload = new LinkedHashMap<>();
        transcriptPayload.put("nodes", editedTranscript);
        transcriptPayload.put("analysisSource", "human_processing_timeline_edit");
        transcriptPayload.put("metadata", humanProcessingEdits);
        video.setTranscriptPayload(transcriptPayload);
        video.setGraphPayload(editedGraph);
        Map<String, Object> metadata = mutableMap(video.getMetadata());
        metadata.put("humanProcessingEdits", humanProcessingEdits);
        video.setMetadata(metadata);
        CreatorShortVideo saved = videoRepository.saveAndFlush(video);

        Map<String, Object> outputPatch = new LinkedHashMap<>();
        outputPatch.put("transcript", editedTranscript);
        outputPatch.put("editedTranscript", editedTranscript);
        outputPatch.put("transcriptGraph", transcriptTimelineGraph(editedTranscript, "human_processing_timeline_edit"));
        outputPatch.put("graph", editedGraph);
        outputPatch.put("editedGraph", editedGraph);
        outputPatch.put("humanTimelineEdited", true);
        outputPatch.put("humanGraphEdited", true);
        outputPatch.put("humanStoryEdited", !storyEdits.isEmpty() || !storyNodes.isEmpty());
        outputPatch.put("humanProcessingEdits", humanProcessingEdits);
        if (!storyEdits.isEmpty()) {
            outputPatch.put("storyEdits", storyEdits);
        }
        if (!storyNodes.isEmpty()) {
            outputPatch.put("storyNodes", storyNodes);
        }
        outputPatch.put("message", "Timeline edits saved. Resume generation to continue.");
        generationJobService.updatePausedGenerationJobPayload(video.getGenerationJobId(), tenantId, userId, outputPatch);
        log.info(
                "Generate Shorts paused timeline edits saved jobId={} videoId={} transcriptNodes={} graphNodes={} storyEdited={}",
                video.getGenerationJobId(),
                video.getId(),
                editedTranscript.size(),
                listOfMaps(editedGraph.get("nodes")).size(),
                !storyEdits.isEmpty() || !storyNodes.isEmpty()
        );
        return toResponse(saved);
    }

    @Transactional
    public ShortGenerationResponse resumeGeneration(UUID videoId, Map<String, Object> payload, String tenantId, String userId) {
        CreatorShortVideo video = loadVideo(videoId, tenantId, userId);
        if (video.getGenerationJobId() == null || video.getSourceAssetId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This short video cannot be resumed because it is missing job or source asset metadata.");
        }
        CreatorGenerationJob existingJob = generationJobService.findGenerationJob(video.getGenerationJobId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Generation job was not found."));
        if (!"PAUSED".equalsIgnoreCase(existingJob.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only paused generation jobs can be resumed.");
        }
        Map<String, Object> requestPayload = mapValue(payload);
        if (!listOfMaps(requestPayload.get("transcript")).isEmpty()
                || !mapValue(requestPayload.get("graph")).isEmpty()
                || !mapValue(requestPayload.get("storyEdits")).isEmpty()
                || !listOfMaps(requestPayload.get("storyNodes")).isEmpty()) {
            saveProcessingTimeline(videoId, requestPayload, tenantId, userId);
            video = loadVideo(videoId, tenantId, userId);
        }

        CreatorAsset sourceAsset = assetRepository.findById(video.getSourceAssetId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Queued source video asset was not found."));
        Map<String, Object> outputPatch = new LinkedHashMap<>();
        outputPatch.put("status", "QUEUED");
        outputPatch.put("shortVideoId", video.getId().toString());
        outputPatch.put("sourceAssetId", sourceAsset.getId().toString());
        boolean explicitAutoResume = isExplicitAutoResume(requestPayload);
        if (explicitAutoResume) {
            outputPatch.put("manualStepMode", false);
            outputPatch.put("executionMode", "AUTO");
            outputPatch.put("manualAdvanceFromStage", "");
            outputPatch.put("manualStepAdvanceFromStage", "");
            outputPatch.put("manualStepDisabledAt", OffsetDateTime.now().toString());
        } else if (isManualStepMode(video, mutableMap(existingJob.getOutputPayload()), requestPayload)) {
            outputPatch.put("manualStepMode", true);
            outputPatch.put("executionMode", "MANUAL_STEP");
            outputPatch.put("manualAdvanceFromStage", defaultString(firstNonEmpty(
                    requestPayload.get("advanceFromStage"),
                    requestPayload.get("manualAdvanceFromStage"),
                    mutableMap(existingJob.getOutputPayload()).get("manualAdvanceFromStage"),
                    mutableMap(existingJob.getOutputPayload()).get("pausedStage"),
                    mutableMap(existingJob.getOutputPayload()).get("activeStage")
            ), ""));
            outputPatch.put("manualStepResumedAt", OffsetDateTime.now().toString());
        }
        CreatorGenerationJob job = generationJobService.resumeGenerationJob(video.getGenerationJobId(), tenantId, userId, outputPatch);

        Map<String, Object> metadata = mutableMap(video.getMetadata());
        Map<String, Object> resumeState = new LinkedHashMap<>();
        resumeState.put("resumedAt", OffsetDateTime.now().toString());
        resumeState.put("jobId", job.getId().toString());
        resumeState.put("executionMode", explicitAutoResume ? "AUTO" : outputPatch.getOrDefault("executionMode", defaultString(mutableMap(video.getSettings()).get("executionMode"), "AUTO")));
        metadata.put("resumeState", resumeState);
        if (explicitAutoResume) {
            Map<String, Object> settings = mutableMap(video.getSettings());
            settings.put("executionMode", "AUTO");
            settings.put("manualStepMode", false);
            video.setSettings(settings);
            metadata.put("executionMode", "AUTO");
        }
        video.setStatus("QUEUED");
        video.setCompletedAt(null);
        video.setMetadata(metadata);
        CreatorShortVideo saved = videoRepository.saveAndFlush(video);

        GenerateShortsRequest resumeRequest = requestFromVideo(saved);
        publishExistingGenerationJobAfterCommit(null, job.getId(), shortsQueuePayload(saved, sourceAsset, resumeRequest));
        log.info(
                "Generate Shorts resumed and requeued jobId={} videoId={} sourceAssetId={} tenantId={} userId={}",
                job.getId(),
                saved.getId(),
                sourceAsset.getId(),
                saved.getTenantId(),
                saved.getUserId()
        );
        return toResponse(saved);
    }

    @Transactional
    public ShortGenerationResponse restartGeneration(UUID videoId, Map<String, Object> payload, String tenantId, String userId) {
        CreatorShortVideo sourceVideo = loadVideo(videoId, tenantId, userId);
        if (sourceVideo.getSourceAssetId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This short video cannot be restarted because it is missing source asset metadata.");
        }
        if (sourceVideo.getGenerationJobId() != null) {
            CreatorGenerationJob existingJob = generationJobService.findGenerationJob(sourceVideo.getGenerationJobId()).orElse(null);
            if (existingJob != null && !isRestartableShortJobStatus(existingJob.getStatus())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "This short video already has a live generation job. Wait for it to finish or pause it before restarting.");
            }
        }

        CreatorAsset sourceAsset = assetRepository.findById(sourceVideo.getSourceAssetId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Queued source video asset was not found."));
        Map<String, Object> requestPayload = mapValue(payload);
        String replayStage = normalizeReplayStage(firstNonEmpty(requestPayload.get("replayStage"), requestPayload.get("stage")));
        String restartMode = replayStage.isBlank()
                ? defaultString(firstNonEmpty(requestPayload.get("mode"), requestPayload.get("restartMode")), "RESTART_FROM_INGESTED")
                : "REPLAY_STAGE";
        GenerateShortsRequest restartRequest = requestFromVideo(sourceVideo);

        UUID restartedVideoId = UUID.randomUUID();
        UUID previousJobId = sourceVideo.getGenerationJobId();
        Map<String, Object> restartState = new LinkedHashMap<>();
        restartState.put("mode", normalizeRestartMode(restartMode));
        restartState.put("replayStage", replayStage.isBlank() ? null : replayStage);
        restartState.put("sourceShortVideoId", sourceVideo.getId().toString());
        restartState.put("newShortVideoId", restartedVideoId.toString());
        restartState.put("previousGenerationJobId", previousJobId == null ? null : previousJobId.toString());
        restartState.put("sourceAssetId", sourceAsset.getId().toString());
        restartState.put("requestedAt", OffsetDateTime.now().toString());
        restartState.put("requestedBy", safeUserId(userId));
        restartState.put("reason", defaultString(requestPayload.get("reason"), replayStage.isBlank() ? "user_restarted_from_ingested_source" : "user_requested_stage_replay"));

        Map<String, Object> jobInput = new LinkedHashMap<>();
        jobInput.put("jobType", JOB_TYPE);
        jobInput.put("shortVideoId", restartedVideoId.toString());
        jobInput.put("sourceAssetId", sourceAsset.getId().toString());
        jobInput.put("settings", mutableMap(sourceVideo.getSettings()));
        jobInput.put("request", requestMap(restartRequest));
        jobInput.put("pipeline", PIPELINE_STAGES);
        jobInput.put("restart", restartState);
        jobInput.put("shortsQueue", Map.of(
                "jobType", JOB_TYPE,
                "sourceAssetId", sourceAsset.getId().toString(),
                "shortVideoId", restartedVideoId.toString(),
                "restartMode", restartState.get("mode"),
                "replayStage", replayStage
        ));

        CreatorGenerationJob job = generationJobService.startGenerationJob(
                JOB_TYPE,
                sourceVideo.getTenantId(),
                sourceVideo.getUserId(),
                sourceVideo.getProjectId(),
                jobInput
        );
        restartState.put("generationJobId", job.getId().toString());

        List<Map<String, Object>> trace = List.of(Map.of(
                "stage", "INGESTION",
                "status", "COMPLETED",
                "summary", "Source video already ingested; Generate Shorts replay queued from stored creator asset.",
                "confidence", 1.0,
                "timestamp", OffsetDateTime.now().toString(),
                "metadata", restartState
        ));
        generationJobService.queueExistingGenerationJob(job.getId(), null, "Generate Shorts restart queued from ingested source", Map.of(
                "shortVideoId", restartedVideoId.toString(),
                "sourceAssetId", sourceAsset.getId().toString(),
                "status", "QUEUED",
                "restart", restartState,
                "activeStage", replayStage.isBlank() ? "INGESTION" : replayStage,
                "trace", trace
        ));

        Map<String, Object> tracePayload = new LinkedHashMap<>();
        tracePayload.put("stages", trace);
        tracePayload.put("pipeline", PIPELINE_STAGES);

        Map<String, Object> restartHistoryItem = new LinkedHashMap<>(restartState);
        Map<String, Object> restartedFrom = new LinkedHashMap<>();
        restartedFrom.put("videoId", sourceVideo.getId().toString());
        restartedFrom.put("generationJobId", previousJobId == null ? null : previousJobId.toString());
        restartedFrom.put("status", sourceVideo.getStatus());
        restartedFrom.put("completedAt", sourceVideo.getCompletedAt() == null ? null : sourceVideo.getCompletedAt().toString());

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "generate_shorts_restart");
        metadata.put("restartState", restartHistoryItem);
        metadata.put("restartHistory", List.of(restartHistoryItem));
        metadata.put("restartedFrom", restartedFrom);
        metadata.put("sourceAssetId", sourceAsset.getId().toString());
        metadata.put("originalVideoId", sourceVideo.getId().toString());
        metadata.put("previousGenerationJobId", previousJobId == null ? null : previousJobId.toString());

        CreatorShortVideo restartedVideo = CreatorShortVideo.builder()
                .id(restartedVideoId)
                .tenantId(sourceVideo.getTenantId())
                .userId(sourceVideo.getUserId())
                .projectId(sourceVideo.getProjectId())
                .sourceAssetId(sourceAsset.getId())
                .generationJobId(job.getId())
                .title(defaultString(sourceVideo.getTitle(), stripExtension(sourceVideo.getOriginalFileName())))
                .originalFileName(sourceVideo.getOriginalFileName())
                .platform(sourceVideo.getPlatform())
                .targetDurationSeconds(sourceVideo.getTargetDurationSeconds())
                .requestedShorts(sourceVideo.getRequestedShorts())
                .reviewMode(sourceVideo.getReviewMode())
                .status("QUEUED")
                .settings(mutableMap(sourceVideo.getSettings()))
                .tracePayload(tracePayload)
                .metadata(metadata)
                .build();
        CreatorShortVideo saved = videoRepository.saveAndFlush(restartedVideo);

        Map<String, Object> queuePayload = shortsQueuePayload(saved, sourceAsset, restartRequest);
        queuePayload.put("restart", restartState);
        queuePayload.put("replayStage", replayStage);
        queuePayload.put("restartMode", restartState.get("mode"));
        Map<String, Object> shortsQueue = mapValue(queuePayload.get("shortsQueue"));
        shortsQueue.put("restartMode", restartState.get("mode"));
        shortsQueue.put("replayStage", replayStage);
        queuePayload.put("shortsQueue", shortsQueue);

        publishExistingGenerationJobAfterCommit(null, job.getId(), queuePayload);
        log.info(
                "Generate Shorts restarted from ingested source jobId={} previousJobId={} sourceVideoId={} newVideoId={} sourceAssetId={} tenantId={} userId={} mode={} replayStage={}",
                job.getId(),
                previousJobId,
                sourceVideo.getId(),
                saved.getId(),
                sourceAsset.getId(),
                saved.getTenantId(),
                saved.getUserId(),
                restartState.get("mode"),
                replayStage
        );
        return toResponse(saved);
    }

    @Transactional
    public ShortGenerationResponse reviewCandidate(
            UUID videoId,
            UUID candidateId,
            ShortCandidateReviewRequest request,
            String tenantId,
            String userId
    ) {
        CreatorShortVideo video = loadVideo(videoId, tenantId, userId);
        CreatorShortCandidate candidate = candidateRepository.findByIdAndVideoId(candidateId, video.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Short candidate was not found."));
        String action = normalizeReviewAction(request == null ? null : request.action());
        Map<String, Object> payload = request == null || request.payload() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(request.payload());

        Map<String, Object> metadata = mutableMap(candidate.getMetadata());
        Map<String, Object> editDecisionList = mutableMap(candidate.getEditDecisionList());
        Map<String, Object> captionPlan = mutableMap(candidate.getCaptionPlan());
        Map<String, Object> renderManifest = mutableMap(candidate.getRenderManifest());

        boolean rerenderAfterSave = false;
        switch (action) {
            case "APPROVE" -> {
                candidate.setStatus("APPROVED");
                candidate.setReviewStatus("APPROVED");
                renderManifest.put("renderStatus", "READY_TO_RENDER");
            }
            case "REJECT" -> {
                candidate.setStatus("REJECTED");
                candidate.setReviewStatus("REJECTED");
            }
            case "LOCK" -> {
                metadata.put("locked", true);
                candidate.setReviewStatus("LOCKED");
            }
            case "PIN" -> {
                metadata.put("pinned", true);
                candidate.setReviewStatus("PINNED");
            }
            case "REPLACE_SEGMENT" -> {
                editDecisionList.put("reviewPatch", payload);
                candidate.setStatus("REPAIR_REQUESTED");
                candidate.setReviewStatus("NEEDS_REPAIR");
            }
            case "MODIFY_HOOK" -> {
                metadata.put("hookOverride", payload);
                candidate.setReviewStatus("NEEDS_REPAIR");
            }
            case "UPDATE_STORY_GRAPH_NODE", "UPDATE_SHORT_TIMELINE", "SAVE_MANUAL_EDL", "MODIFY_STORY_INTENT", "MODIFY_STORY_BEATS" -> {
                applyStoryGraphNodePatch(video, candidate, metadata, editDecisionList, renderManifest, payload);
            }
            case "UPDATE_RETENTION_PLAN", "SAVE_RETENTION_PLAN", "APPLY_RETENTION_EFFECTS" -> {
                applyRetentionPlanPatch(candidate, metadata, renderManifest, payload);
            }
            case "RERENDER_SHORT_CANDIDATE", "RENDER_SHORT_CANDIDATE" -> {
                renderManifest.put("renderStatus", "RERENDER_REQUESTED");
                metadata.put("rerenderRequestedAt", OffsetDateTime.now().toString());
                candidate.setStatus("RERENDER_REQUESTED");
                candidate.setReviewStatus("NEEDS_RENDER");
                rerenderAfterSave = true;
            }
            case "MODIFY_CAPTIONS" -> {
                applyCaptionPatch(candidate, captionPlan, renderManifest, payload);
            }
            case "REGENERATE" -> {
                metadata.put("regenerateRequest", payload);
                candidate.setStatus("REGENERATE_REQUESTED");
                candidate.setReviewStatus("NEEDS_REPAIR");
            }
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported review action: " + action);
        }

        appendReviewEvent(metadata, action, payload);
        candidate.setMetadata(metadata);
        candidate.setEditDecisionList(editDecisionList);
        candidate.setCaptionPlan(captionPlan);
        candidate.setRenderManifest(renderManifest);
        candidateRepository.saveAndFlush(candidate);
        videoRepository.saveAndFlush(video);
        if (rerenderAfterSave) {
            queueCandidateRerenderJob(video, candidate, payload);
        }
        return toResponse(videoRepository.findById(video.getId()).orElse(video));
    }

    private void applyCaptionPatch(
            CreatorShortCandidate candidate,
            Map<String, Object> captionPlan,
            Map<String, Object> renderManifest,
            Map<String, Object> payload
    ) {
        Object captionSource = firstNonEmpty(payload.get("captions"), mapValue(payload.get("captionPlan")).get("captions"));
        List<Map<String, Object>> requestedCaptions = listOfMaps(captionSource);
        List<Map<String, Object>> sanitizedCaptions = new ArrayList<>();
        int index = 0;
        for (Map<String, Object> caption : requestedCaptions) {
            String text = truncate(stringValue(firstNonEmpty(
                    caption.get("text"),
                    caption.get("caption"),
                    caption.get("line"),
                    caption.get("displayText")
            )).trim(), 220);
            if (text.isBlank()) {
                continue;
            }
            double start = doubleValue(firstNonEmpty(caption.get("start"), caption.get("sourceStart"), caption.get("timelineStart")), index * 2.0);
            double end = doubleValue(firstNonEmpty(caption.get("end"), caption.get("sourceEnd"), caption.get("timelineEnd")), start + 2.0);
            if (end <= start + 0.1) {
                end = start + 0.1;
            }
            Map<String, Object> sanitized = new LinkedHashMap<>(caption);
            sanitized.put("id", defaultString(firstNonEmpty(caption.get("id"), caption.get("captionId")), "caption-" + index));
            sanitized.put("captionId", sanitized.get("id"));
            sanitized.put("start", roundSeconds(start));
            sanitized.put("end", roundSeconds(end));
            sanitized.put("text", text);
            sanitized.put("humanEdited", true);
            sanitized.put("contextLocked", true);
            sanitizedCaptions.add(sanitized);
            index++;
        }

        captionPlan.put("captions", sanitizedCaptions);
        captionPlan.put("reviewPatch", payload);
        captionPlan.put("removedCaptionIds", payload.getOrDefault("removedCaptionIds", List.of()));
        captionPlan.put("humanEdited", true);
        captionPlan.put("contextLocked", true);
        captionPlan.put("source", "human_caption_editor");
        captionPlan.put("updatedAt", OffsetDateTime.now().toString());
        renderManifest.put("captionPlanEdited", true);
        renderManifest.put("renderStatus", "NEEDS_RERENDER");
        candidate.setStatus("NEEDS_RERENDER");
        candidate.setReviewStatus("NEEDS_RENDER");
    }

    private void applyRetentionPlanPatch(
            CreatorShortCandidate candidate,
            Map<String, Object> metadata,
            Map<String, Object> renderManifest,
            Map<String, Object> payload
    ) {
        List<Map<String, Object>> retentionPlan = sanitizeManualRetentionPlan(
                listOfMaps(firstNonEmpty(payload.get("retentionPlan"), payload.get("manualRetentionPlan"), payload.get("effects"))),
                candidate
        );
        String updatedAt = OffsetDateTime.now().toString();
        metadata.put("manualRetentionPlan", retentionPlan);
        metadata.put("manualRetentionPlanEdited", true);
        metadata.put("manualRetentionPlanUpdatedAt", updatedAt);
        renderManifest.put("manualRetentionPlan", retentionPlan);
        renderManifest.put("retentionPlanSource", "human_retention_editor");
        renderManifest.put("retentionPlanEdited", true);
        renderManifest.put("manualRetentionPlanUpdatedAt", updatedAt);
        renderManifest.put("renderStatus", "NEEDS_RERENDER");
        candidate.setStatus("NEEDS_RERENDER");
        candidate.setReviewStatus("NEEDS_RENDER");
    }

    private List<Map<String, Object>> sanitizeManualRetentionPlan(List<Map<String, Object>> requestedPlan, CreatorShortCandidate candidate) {
        double duration = Math.max(0.1, candidateDurationSeconds(candidate));
        List<Map<String, Object>> sanitized = new ArrayList<>();
        int index = 0;
        for (Map<String, Object> effect : requestedPlan == null ? List.<Map<String, Object>>of() : requestedPlan) {
            String type = normalizeRetentionEffectType(firstNonEmpty(effect.get("type"), effect.get("effectType"), effect.get("kind")));
            double start = Math.max(0.0, Math.min(duration, doubleValue(firstNonEmpty(effect.get("start"), effect.get("timelineStart")), index * 3.0)));
            double end = Math.max(start + 0.25, doubleValue(firstNonEmpty(effect.get("end"), effect.get("timelineEnd")), start + 2.5));
            end = Math.min(duration, end);
            if (end <= start) {
                end = Math.min(duration, start + 0.5);
            }
            Map<String, Object> sanitizedEffect = new LinkedHashMap<>();
            sanitizedEffect.put("id", defaultString(firstNonEmpty(effect.get("id"), effect.get("effectId")), "retention-effect-%03d".formatted(index + 1)));
            sanitizedEffect.put("type", type);
            sanitizedEffect.put("start", roundSeconds(start));
            sanitizedEffect.put("end", roundSeconds(end));
            sanitizedEffect.put("durationSeconds", roundSeconds(end - start));
            sanitizedEffect.put("text", truncate(stringValue(firstNonEmpty(effect.get("text"), effect.get("label"), effect.get("overlayText"))).trim(), 120));
            sanitizedEffect.put("reason", truncate(stringValue(effect.get("reason")).trim(), 240));
            sanitizedEffect.put("instruction", truncate(stringValue(effect.get("instruction")).trim(), 280));
            sanitizedEffect.put("intensity", Math.max(0.0, Math.min(1.0, doubleValue(effect.get("intensity"), 0.65))));
            sanitizedEffect.put("source", "human_retention_editor");
            sanitizedEffect.put("manual", true);
            sanitized.add(sanitizedEffect);
            index++;
        }
        return sanitized;
    }

    private String normalizeRetentionEffectType(Object value) {
        String normalized = defaultString(value, "PUNCH_ZOOM").trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        return switch (normalized) {
            case "ZOOM", "PUNCH", "PUNCH_ZOOM", "ZOOM_IN", "ZOOM_OUT", "TEXT_POP", "TEXT", "B_ROLL", "BROLL", "CUTAWAY", "HIGHLIGHT", "SHAKE", "SCREENSHOT", "MEME", "MOTION_GRAPHIC" -> normalized.equals("TEXT") ? "TEXT_POP" : (normalized.equals("BROLL") ? "B_ROLL" : normalized);
            default -> "PUNCH_ZOOM";
        };
    }

    private double candidateDurationSeconds(CreatorShortCandidate candidate) {
        Map<String, Object> editDecisionList = mutableMap(candidate == null ? null : candidate.getEditDecisionList());
        double explicit = doubleValue(firstNonEmpty(editDecisionList.get("actualDurationSeconds"), editDecisionList.get("durationSeconds"), editDecisionList.get("timelineEnd")), 0.0);
        if (explicit > 0.0) {
            return explicit;
        }
        double total = 0.0;
        for (Map<String, Object> segment : listOfMaps(editDecisionList.get("segments"))) {
            double start = doubleValue(firstNonEmpty(segment.get("sourceStart"), segment.get("start")), 0.0);
            double end = Math.max(start, doubleValue(firstNonEmpty(segment.get("sourceEnd"), segment.get("end")), start));
            total += Math.max(0.0, end - start);
        }
        return total > 0.0 ? total : 60.0;
    }

    private void applyStoryGraphNodePatch(
            CreatorShortVideo video,
            CreatorShortCandidate candidate,
            Map<String, Object> metadata,
            Map<String, Object> editDecisionList,
            Map<String, Object> renderManifest,
            Map<String, Object> payload
    ) {
        Map<String, Object> patch = sanitizeStoryGraphPatch(video, candidate, editDecisionList, payload);
        Map<String, Object> semanticReview = humanSemanticReview(video, metadata, editDecisionList, patch);
        if (semanticReviewHasBlockingIssue(semanticReview)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Edited intent, hook, and beats must stay supported by the original story nodes.");
        }
        patch.put("semanticReview", semanticReview);
        metadata.put("humanStoryGraphPatch", patch);
        metadata.put("humanEdited", true);
        metadata.put("contextLocked", true);
        metadata.put("contextValidation", patch.get("contextValidation"));
        metadata.put("humanSemanticReview", semanticReview);
        renderManifest.put("humanSemanticReview", semanticReview);
        renderManifest.put("humanContextReviewRequired", Boolean.TRUE.equals(semanticReview.get("requiresHumanReview")));

        String intent = stringValue(patch.get("intent")).trim();
        if (!intent.isBlank()) {
            Map<String, Object> storyIntent = firstNonEmptyMap(
                    metadata.get("storyIntent"),
                    mapValue(metadata.get("compressionPlanMetadata")).get("storyIntent")
            );
            storyIntent.put("viewerIntent", intent);
            storyIntent.put("humanIntent", intent);
            storyIntent.put("humanEdited", true);
            storyIntent.put("contextLocked", true);
            metadata.put("storyIntent", storyIntent);
        }

        String hook = stringValue(patch.get("hook")).trim();
        if (!hook.isBlank()) {
            Map<String, Object> hookOverride = new LinkedHashMap<>();
            hookOverride.put("openingLine", hook);
            hookOverride.put("humanEdited", true);
            hookOverride.put("contextLocked", true);
            metadata.put("hookOverride", hookOverride);

            Map<String, Object> hookPlan = mutableMap(mapValue(metadata.get("hookPlan")));
            hookPlan.put("openingLine", hook);
            hookPlan.put("humanEdited", true);
            hookPlan.put("contextLocked", true);
            metadata.put("hookPlan", hookPlan);
        }

        List<Map<String, Object>> manualSegments = listOfMaps(patch.get("segments"));
        if (!manualSegments.isEmpty()) {
            editDecisionList.put("segments", manualSegments);
            editDecisionList.put("exactTimestampEdl", true);
            editDecisionList.put("manualTimelineEdited", true);
            editDecisionList.put("contextLocked", true);
            editDecisionList.put("source", "human_graph_timeline_editor");
            editDecisionList.put("actualDurationSeconds", patch.get("timelineDurationSeconds"));
            editDecisionList.put("timelineEnd", patch.get("timelineDurationSeconds"));
            editDecisionList.put("updatedAt", patch.get("updatedAt"));
            metadata.put("manualTimelineEdited", true);
            metadata.put("manualTimelineSource", "human_graph_timeline_editor");
            metadata.put("manualTimelineDurationSeconds", patch.get("timelineDurationSeconds"));
            renderManifest.put("manualTimelineEdited", true);
            renderManifest.put("manualTimelineDurationSeconds", patch.get("timelineDurationSeconds"));
        }

        List<Map<String, Object>> requestedBeats = listOfMaps(patch.get("beats"));
        if (!requestedBeats.isEmpty()) {
            Map<String, Object> storyBeatPlan = firstNonEmptyMap(
                    metadata.get("storyBeatPlan"),
                    mapValue(metadata.get("compressionPlanMetadata")).get("storyBeatPlan")
            );
            List<Map<String, Object>> existingBeats = listOfMaps(storyBeatPlan.get("storyBeats"));
            if (existingBeats.isEmpty()) {
                existingBeats = listOfMaps(storyBeatPlan.get("beats"));
            }
            Map<String, String> textByBeatId = new LinkedHashMap<>();
            Map<String, String> textByNodeId = new LinkedHashMap<>();
            for (Map<String, Object> beat : requestedBeats) {
                String text = stringValue(beat.get("text")).trim();
                if (text.isBlank()) {
                    continue;
                }
                String beatId = stringValue(beat.get("beatId")).trim();
                String nodeId = stringValue(beat.get("nodeId")).trim();
                if (!beatId.isBlank()) {
                    textByBeatId.put(beatId, text);
                }
                if (!nodeId.isBlank()) {
                    textByNodeId.put(nodeId, text);
                }
            }
            List<Map<String, Object>> updatedBeats = new ArrayList<>();
            int index = 0;
            for (Map<String, Object> beat : existingBeats) {
                Map<String, Object> updatedBeat = mutableMap(beat);
                String beatId = defaultString(updatedBeat.get("beatId"), defaultString(updatedBeat.get("id"), "beat-" + index));
                String nodeId = stringValue(updatedBeat.get("nodeId")).trim();
                String replacement = textByBeatId.get(beatId);
                if ((replacement == null || replacement.isBlank()) && !nodeId.isBlank()) {
                    replacement = textByNodeId.get(nodeId);
                }
                if (replacement != null && !replacement.isBlank()) {
                    updatedBeat.put("summary", replacement);
                    updatedBeat.put("text", replacement);
                    updatedBeat.put("humanText", replacement);
                    updatedBeat.put("humanEdited", true);
                }
                updatedBeats.add(updatedBeat);
                index++;
            }
            if (!updatedBeats.isEmpty()) {
                storyBeatPlan.put("storyBeats", updatedBeats);
            }
            storyBeatPlan.put("humanEdited", true);
            storyBeatPlan.put("contextLocked", true);
            metadata.put("storyBeatPlan", storyBeatPlan);
        }
        boolean retentionEdited = payload.containsKey("retentionPlan") || payload.containsKey("manualRetentionPlan") || payload.containsKey("effects");
        if (retentionEdited) {
            applyRetentionPlanPatch(candidate, metadata, renderManifest, payload);
        }

        boolean timelineEdited = !manualSegments.isEmpty();
        boolean needsContextReview = Boolean.TRUE.equals(semanticReview.get("requiresHumanReview"));
        boolean needsRender = timelineEdited || retentionEdited;
        renderManifest.put("reviewStatus", needsContextReview ? "NEEDS_CONTEXT_REVIEW" : needsRender ? "NEEDS_RENDER" : "NEEDS_REPAIR");
        renderManifest.put("humanStoryGraphPatch", patch);
        renderManifest.put("renderStatus", needsRender ? "NEEDS_RERENDER" : needsContextReview ? "NEEDS_CONTEXT_REVIEW" : "NEEDS_REPAIR");
        candidate.setStatus(needsRender ? "NEEDS_RERENDER" : needsContextReview ? "NEEDS_CONTEXT_REVIEW" : "REPAIR_REQUESTED");
        candidate.setReviewStatus(needsContextReview ? "NEEDS_CONTEXT_REVIEW" : needsRender ? "NEEDS_RENDER" : "NEEDS_REPAIR");
        updateShortGraphNodePatch(video, candidate, patch, metadata, editDecisionList);
    }

    private UUID queueCandidateRerenderJob(CreatorShortVideo video, CreatorShortCandidate candidate, Map<String, Object> payload) {
        CreatorAsset sourceAsset = assetRepository.findById(video.getSourceAssetId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Source video asset was not found."));
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("jobType", JOB_TYPE_RERENDER);
        input.put("shortVideoId", video.getId().toString());
        input.put("candidateId", candidate.getId().toString());
        input.put("sourceAssetId", sourceAsset.getId().toString());
        input.put("tenantId", video.getTenantId());
        input.put("userId", video.getUserId());
        input.put("projectId", video.getProjectId() == null ? null : video.getProjectId().toString());
        input.put("requestedAt", OffsetDateTime.now().toString());
        input.put("request", payload == null ? new LinkedHashMap<>() : new LinkedHashMap<>(payload));
        input.put("shortsQueue", Map.of(
                "jobType", JOB_TYPE_RERENDER,
                "shortVideoId", video.getId().toString(),
                "candidateId", candidate.getId().toString(),
                "sourceAssetId", sourceAsset.getId().toString()
        ));

        CreatorGenerationJob job = generationJobService.startGenerationJob(
                JOB_TYPE_RERENDER,
                video.getTenantId(),
                video.getUserId(),
                video.getProjectId(),
                input
        );
        generationJobService.queueExistingGenerationJob(job.getId(), null, "Short candidate re-render queued", Map.of(
                "shortVideoId", video.getId().toString(),
                "candidateId", candidate.getId().toString(),
                "sourceAssetId", sourceAsset.getId().toString(),
                "status", "QUEUED"
        ));
        publishExistingGenerationJobAfterCommit(null, job.getId(), input);

        Map<String, Object> candidateMetadata = mutableMap(candidate.getMetadata());
        Map<String, Object> renderManifest = mutableMap(candidate.getRenderManifest());
        candidateMetadata.put("rerenderJobId", job.getId().toString());
        candidateMetadata.put("rerenderQueuedAt", OffsetDateTime.now().toString());
        renderManifest.put("rerenderJobId", job.getId().toString());
        renderManifest.put("renderStatus", "RERENDER_QUEUED");
        candidate.setMetadata(candidateMetadata);
        candidate.setRenderManifest(renderManifest);
        candidate.setStatus("RERENDER_QUEUED");
        candidate.setReviewStatus("NEEDS_RENDER");
        candidateRepository.saveAndFlush(candidate);

        Map<String, Object> videoMetadata = mutableMap(video.getMetadata());
        videoMetadata.put("lastManualRerenderJobId", job.getId().toString());
        videoMetadata.put("lastManualRerenderCandidateId", candidate.getId().toString());
        videoMetadata.put("lastManualRerenderQueuedAt", OffsetDateTime.now().toString());
        video.setMetadata(videoMetadata);
        video.setStatus("RERENDER_QUEUED");
        videoRepository.saveAndFlush(video);
        return job.getId();
    }

    public void runQueuedCandidateRerenderJob(UUID jobId, UUID videoId, UUID candidateId) {
        try {
            generationJobService.updateGenerationJobProgress(jobId, 15, "Short candidate re-render worker picked queued job", Map.of(
                    "shortVideoId", videoId == null ? "" : videoId.toString(),
                    "candidateId", candidateId == null ? "" : candidateId.toString()
            ));
            Map<String, Object> output = rerenderSingleCandidate(videoId, candidateId);
            if (Boolean.TRUE.equals(output.get("rerenderFailed"))) {
                generationJobService.failGenerationJob(jobId, defaultString(output.get("failureReason"), "Short candidate re-render failed."), output);
            } else {
                generationJobService.completeGenerationJob(jobId, output);
            }
        } catch (ResponseStatusException ex) {
            if (videoId != null && candidateId != null) {
                markCandidateRerenderFailed(videoId, candidateId, defaultString(ex.getReason(), "Short candidate re-render failed."));
            }
            generationJobService.failGenerationJob(jobId, defaultString(ex.getReason(), "Short candidate re-render failed."));
            throw ex;
        } catch (Exception ex) {
            if (videoId != null && candidateId != null) {
                markCandidateRerenderFailed(videoId, candidateId, defaultString(ex.getMessage(), "Short candidate re-render failed."));
            }
            generationJobService.failGenerationJob(jobId, defaultString(ex.getMessage(), "Short candidate re-render failed."));
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Short candidate re-render failed.", ex);
        }
    }

    private Map<String, Object> rerenderSingleCandidate(UUID videoId, UUID candidateId) {
        CreatorShortVideo video = videoRepository.findById(videoId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Short video was not found."));
        CreatorAsset sourceAsset = assetRepository.findById(video.getSourceAssetId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Source video asset was not found."));
        CreatorShortCandidate candidate = candidateRepository.findByIdAndVideoId(candidateId, video.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Short candidate was not found."));

        ShortRenderingService.RenderedShortsResult rendering = shortRenderingService.render(video, sourceAsset, List.of(candidate));
        CreatorShortCandidate renderedCandidate = candidateRepository.findByIdAndVideoId(candidateId, video.getId()).orElse(candidate);
        ShortPostRenderQaService.PostRenderQaResult postRenderQa = postRenderQaService.audit(video, List.of(renderedCandidate));

        Map<String, Object> metadata = mutableMap(video.getMetadata());
        metadata.put("lastManualRerender", Map.of(
                "candidateId", candidateId.toString(),
                "rendering", rendering.metadata(),
                "postRenderQa", postRenderQa.metadata(),
                "updatedAt", OffsetDateTime.now().toString()
        ));
        List<CreatorShortCandidate> allCandidates = candidateRepository.findByVideoIdOrderByRankIndexAsc(video.getId());
        CreatorShortCandidate latestCandidate = allCandidates.stream()
                .filter(item -> candidateId.equals(item.getId()))
                .findFirst()
                .orElse(renderedCandidate);
        Map<String, Object> aggregate = aggregateShortPipelineStatus(allCandidates);
        boolean rerenderFailed = !isUsableRenderedCandidate(latestCandidate);
        metadata.put("pipelineOutcome", aggregate);
        video.setMetadata(metadata);
        video.setStatus(stringValue(aggregate.get("videoStatus")));
        video.setGraphPayload(withShortCandidateGraphNodes(
                mutableMap(video.getGraphPayload()),
                allCandidates
        ));
        videoRepository.saveAndFlush(video);

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("shortVideoId", video.getId().toString());
        output.put("candidateId", candidateId.toString());
        output.put("candidateStatus", latestCandidate.getStatus());
        output.put("rendering", rendering.metadata());
        output.put("postRenderQa", postRenderQa.metadata());
        output.put("pipelineOutcome", aggregate);
        output.put("rerenderFailed", rerenderFailed);
        if (rerenderFailed) {
            output.put("failureReason", "Re-rendered candidate did not pass render/QA.");
        }
        return output;
    }

    private void markCandidateRerenderFailed(UUID videoId, UUID candidateId, String reason) {
        CreatorShortVideo video = videoRepository.findById(videoId).orElse(null);
        candidateRepository.findByIdAndVideoId(candidateId, videoId).ifPresent(candidate -> {
            Map<String, Object> metadata = mutableMap(candidate.getMetadata());
            Map<String, Object> renderManifest = mutableMap(candidate.getRenderManifest());
            metadata.put("rerenderFailure", reason);
            renderManifest.put("renderStatus", "RERENDER_FAILED");
            renderManifest.put("renderFailure", Map.of("message", reason, "updatedAt", OffsetDateTime.now().toString()));
            candidate.setMetadata(metadata);
            candidate.setRenderManifest(renderManifest);
            candidate.setStatus("RERENDER_FAILED");
            candidate.setReviewStatus("NEEDS_RENDER");
            candidateRepository.save(candidate);
        });
        if (video != null) {
            List<CreatorShortCandidate> candidates = candidateRepository.findByVideoIdOrderByRankIndexAsc(video.getId());
            Map<String, Object> metadata = mutableMap(video.getMetadata());
            metadata.put("lastManualRerenderFailure", reason);
            metadata.put("pipelineOutcome", aggregateShortPipelineStatus(candidates));
            video.setMetadata(metadata);
            video.setStatus(stringValue(metadata.get("pipelineOutcome") instanceof Map<?, ?> map ? map.get("videoStatus") : "READY_FOR_REVIEW_WITH_WARNINGS"));
            videoRepository.save(video);
        }
    }

    private Map<String, Object> sanitizeStoryGraphPatch(CreatorShortVideo video, CreatorShortCandidate candidate, Map<String, Object> editDecisionList, Map<String, Object> payload) {
        List<String> allowedSourceNodeIds = sourceNodeIds(editDecisionList);
        java.util.Set<String> allowedNodeSet = new java.util.HashSet<>(allowedSourceNodeIds);
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("graphNodeId", defaultString(payload.get("graphNodeId"), "short-" + candidate.getId()));
        patch.put("candidateId", candidate.getId().toString());
        patch.put("intent", truncate(stringValue(payload.get("intent")).trim(), 220));
        patch.put("hook", truncate(stringValue(payload.get("hook")).trim(), 120));
        patch.put("contextLocked", true);

        List<Map<String, Object>> segments = sanitizeManualTimelineSegments(video, editDecisionList, payload);
        if (!segments.isEmpty()) {
            patch.put("segments", segments);
            patch.put("timelineDurationSeconds", roundSeconds(timelineDuration(segments)));
        }

        List<Map<String, Object>> beats = new ArrayList<>();
        for (Map<String, Object> beat : listOfMaps(payload.get("beats"))) {
            String nodeId = stringValue(beat.get("nodeId")).trim();
            if (!nodeId.isBlank() && !allowedNodeSet.isEmpty() && !allowedNodeSet.contains(nodeId)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Beat edits must stay inside the original short source nodes.");
            }
            String text = truncate(stringValue(beat.get("text")).trim(), 180);
            if (text.isBlank()) {
                continue;
            }
            Map<String, Object> sanitized = new LinkedHashMap<>();
            String beatId = defaultString(beat.get("beatId"), stringValue(beat.get("id")).trim());
            if (!beatId.isBlank()) {
                sanitized.put("beatId", beatId);
            }
            if (!nodeId.isBlank()) {
                sanitized.put("nodeId", nodeId);
            }
            sanitized.put("text", text);
            sanitized.put("sourceLocked", true);
            beats.add(sanitized);
        }
        patch.put("beats", beats);
        patch.put("updatedAt", OffsetDateTime.now().toString());
        patch.put("contextValidation", Map.of(
                "status", "SOURCE_LOCKED",
                "mustStayInsideOriginalStory", true,
                "rejectsUnknownSourceNodes", true,
                "allowedSourceNodeIds", allowedSourceNodeIds
        ));
        return patch;
    }

    private List<Map<String, Object>> sanitizeManualTimelineSegments(CreatorShortVideo video, Map<String, Object> editDecisionList, Map<String, Object> payload) {
        List<Map<String, Object>> requested = listOfMaps(payload.get("segments"));
        if (requested.isEmpty()) {
            return new ArrayList<>();
        }

        List<Map<String, Object>> originalSegments = listOfMaps(editDecisionList.get("segments"));
        List<Map<String, Object>> sourceTimelineSegments = sourceTimelineSegments(video);
        Map<String, Map<String, Object>> originalBySegmentId = new LinkedHashMap<>();
        Map<String, Map<String, Object>> originalByNodeId = new LinkedHashMap<>();
        Map<String, Map<String, Object>> sourceBySegmentId = new LinkedHashMap<>();
        Map<String, Map<String, Object>> sourceByNodeId = new LinkedHashMap<>();
        Map<String, Map<String, Object>> sourceBySceneId = new LinkedHashMap<>();
        java.util.Set<String> allowedSceneIds = new java.util.HashSet<>(sceneIds(editDecisionList));
        for (Map<String, Object> original : originalSegments) {
            String segmentId = stringValue(firstNonEmpty(original.get("segmentId"), original.get("id"))).trim();
            String nodeId = stringValue(original.get("nodeId")).trim();
            if (!segmentId.isBlank()) {
                originalBySegmentId.put(segmentId, original);
            }
            if (!nodeId.isBlank()) {
                originalByNodeId.putIfAbsent(nodeId, original);
            }
        }
        for (Map<String, Object> sourceSegment : sourceTimelineSegments) {
            String segmentId = stringValue(firstNonEmpty(sourceSegment.get("segmentId"), sourceSegment.get("id"))).trim();
            String nodeId = stringValue(sourceSegment.get("nodeId")).trim();
            String sceneId = stringValue(sourceSegment.get("sceneId")).trim();
            if (!segmentId.isBlank()) {
                sourceBySegmentId.put(segmentId, sourceSegment);
            }
            if (!nodeId.isBlank()) {
                sourceByNodeId.putIfAbsent(nodeId, sourceSegment);
            }
            if (!sceneId.isBlank()) {
                sourceBySceneId.putIfAbsent(sceneId, sourceSegment);
                allowedSceneIds.add(sceneId);
            }
        }

        List<Map<String, Object>> sanitized = new ArrayList<>();
        double timeline = 0.0;
        int index = 0;
        for (Map<String, Object> requestedSegment : requested) {
            String segmentId = stringValue(firstNonEmpty(requestedSegment.get("segmentId"), requestedSegment.get("id"))).trim();
            String nodeId = stringValue(requestedSegment.get("nodeId")).trim();
            String requestedSceneId = stringValue(requestedSegment.get("sceneId")).trim();
            Map<String, Object> original = !segmentId.isBlank() ? originalBySegmentId.get(segmentId) : null;
            if (original == null && !nodeId.isBlank()) {
                original = originalByNodeId.get(nodeId);
            }
            if (original == null && !segmentId.isBlank()) {
                original = sourceBySegmentId.get(segmentId);
            }
            if (original == null && !nodeId.isBlank()) {
                original = sourceByNodeId.get(nodeId);
            }
            if (original == null && !requestedSceneId.isBlank()) {
                original = sourceBySceneId.get(requestedSceneId);
            }
            if (original == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Manual timeline segments must come from the original short EDL or stored source timeline evidence.");
            }
            String originalNodeId = stringValue(original.get("nodeId")).trim();
            if (!originalNodeId.isBlank() && !nodeId.isBlank() && !originalNodeId.equals(nodeId)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Manual timeline segment node id cannot change.");
            }
            String sceneId = defaultString(requestedSegment.get("sceneId"), stringValue(original.get("sceneId")));
            if (!sceneId.isBlank() && !allowedSceneIds.isEmpty() && !allowedSceneIds.contains(sceneId)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Manual timeline scenes must stay inside original EDL scenes or stored source timeline scenes.");
            }

            double originalStart = doubleValue(firstNonEmpty(original.get("sourceStart"), original.get("start")), 0.0);
            double originalEnd = doubleValue(firstNonEmpty(original.get("sourceEnd"), original.get("end")), originalStart + 0.1);
            double requestedStart = doubleValue(firstNonEmpty(requestedSegment.get("sourceStart"), requestedSegment.get("start")), originalStart);
            double requestedEnd = doubleValue(firstNonEmpty(requestedSegment.get("sourceEnd"), requestedSegment.get("end")), originalEnd);
            if (requestedStart < originalStart - 0.05 || requestedEnd > originalEnd + 0.05 || requestedEnd <= requestedStart + 0.1) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Manual trims must stay inside each original source segment.");
            }

            double duration = requestedEnd - requestedStart;
            Map<String, Object> segment = new LinkedHashMap<>(original);
            segment.put("segmentId", segmentId.isBlank() ? defaultString(original.get("segmentId"), "manual-" + index) : segmentId);
            segment.put("nodeId", nodeId.isBlank() ? originalNodeId : nodeId);
            segment.put("sceneId", sceneId);
            segment.put("originalSourceStart", roundSeconds(originalStart));
            segment.put("originalSourceEnd", roundSeconds(originalEnd));
            segment.put("sourceStart", roundSeconds(requestedStart));
            segment.put("sourceEnd", roundSeconds(requestedEnd));
            segment.put("timelineStart", roundSeconds(timeline));
            segment.put("timelineEnd", roundSeconds(timeline + duration));
            segment.put("durationSeconds", roundSeconds(duration));
            segment.put("locked", booleanValue(requestedSegment.get("locked"), booleanValue(original.get("locked"), false)));
            segment.put("manualEdited", true);
            segment.put("contextLocked", true);
            sanitized.add(segment);
            timeline += duration;
            index++;
        }

        double target = Math.max(15.0, video == null || video.getTargetDurationSeconds() == null ? 60.0 : video.getTargetDurationSeconds());
        if (timeline > target + 5.0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Manual timeline exceeds target duration by more than five seconds.");
        }
        return sanitized;
    }

    private List<Map<String, Object>> sourceTimelineSegments(CreatorShortVideo video) {
        if (video == null) {
            return new ArrayList<>();
        }
        Map<String, Object> metadata = mutableMap(video.getMetadata());
        Map<String, Object> transcriptPayload = mutableMap(video.getTranscriptPayload());
        Map<String, Object> sceneTimeline = mapValue(metadata.get("sceneTimeline"));
        List<Map<String, Object>> scenes = listOfMaps(sceneTimeline.get("scenes"));
        if (scenes.isEmpty()) {
            scenes = listOfMaps(mapValue(metadata.get("sceneAnalysis")).get("scenes"));
        }
        List<Map<String, Object>> transcript = listOfMaps(firstNonEmpty(
                transcriptPayload.get("nodes"),
                transcriptPayload.get("transcript"),
                transcriptPayload.get("segments"),
                transcriptPayload.get("items")
        ));
        List<Map<String, Object>> frames = listOfMaps(firstNonEmpty(
                metadata.get("frames"),
                mapValue(metadata.get("sceneAnalysis")).get("frames")
        ));

        List<Map<String, Object>> segments = new ArrayList<>();
        if (!scenes.isEmpty()) {
            int index = 0;
            for (Map<String, Object> scene : scenes) {
                double start = doubleValue(firstNonEmpty(scene.get("start"), scene.get("sourceStart")), (double) index);
                double end = Math.max(start + 0.1, doubleValue(firstNonEmpty(scene.get("end"), scene.get("sourceEnd")), start + 1.0));
                String sceneId = defaultString(firstNonEmpty(scene.get("id"), scene.get("sceneId")), "scene-" + index);
                List<Map<String, Object>> sceneTranscript = listOfMaps(scene.get("transcript"));
                if (sceneTranscript.isEmpty()) {
                    sceneTranscript = transcriptForWindow(transcript, start, end);
                }
                String nodeId = sceneTranscript.isEmpty() ? "" : defaultString(firstNonEmpty(sceneTranscript.get(0).get("id"), sceneTranscript.get(0).get("nodeId")), "");
                String speaker = sceneTranscript.isEmpty() ? "" : defaultString(firstNonEmpty(sceneTranscript.get(0).get("speaker"), sceneTranscript.get(0).get("role")), "");
                Map<String, Object> segment = new LinkedHashMap<>(scene);
                segment.put("segmentId", "source-scene-" + sceneId);
                segment.put("id", "source-scene-" + sceneId);
                segment.put("nodeId", nodeId);
                segment.put("sceneId", sceneId);
                segment.put("speaker", speaker);
                segment.put("activeSpeaker", speaker);
                segment.put("sourceStart", roundSeconds(start));
                segment.put("sourceEnd", roundSeconds(end));
                segment.put("start", roundSeconds(start));
                segment.put("end", roundSeconds(end));
                segment.put("originalSourceStart", roundSeconds(start));
                segment.put("originalSourceEnd", roundSeconds(end));
                segment.put("label", defaultString(scene.get("label"), "Source scene " + (index + 1)));
                segment.put("transcript", transcriptText(sceneTranscript));
                segment.put("transcriptNodes", sceneTranscript);
                segment.put("frames", firstNonEmpty(scene.get("frames"), scene.get("frameIds"), frameIdsForWindow(frames, start, end)));
                segment.put("source", "stored_source_timeline");
                segments.add(segment);
                index++;
            }
            return segments;
        }

        int index = 0;
        for (Map<String, Object> node : transcript) {
            double start = doubleValue(firstNonEmpty(node.get("start"), node.get("sourceStart"), node.get("timelineStart")), (double) index);
            double end = Math.max(start + 0.1, doubleValue(firstNonEmpty(node.get("end"), node.get("sourceEnd"), node.get("timelineEnd")), start + 1.0));
            String nodeId = defaultString(firstNonEmpty(node.get("id"), node.get("nodeId")), "node-" + index);
            String sceneId = stringValue(node.get("sceneId")).trim();
            String speaker = defaultString(firstNonEmpty(node.get("speaker"), node.get("role")), "");
            Map<String, Object> segment = new LinkedHashMap<>(node);
            segment.put("segmentId", "source-node-" + nodeId);
            segment.put("id", "source-node-" + nodeId);
            segment.put("nodeId", nodeId);
            segment.put("sceneId", sceneId);
            segment.put("speaker", speaker);
            segment.put("activeSpeaker", speaker);
            segment.put("sourceStart", roundSeconds(start));
            segment.put("sourceEnd", roundSeconds(end));
            segment.put("start", roundSeconds(start));
            segment.put("end", roundSeconds(end));
            segment.put("originalSourceStart", roundSeconds(start));
            segment.put("originalSourceEnd", roundSeconds(end));
            segment.put("label", defaultString(firstNonEmpty(node.get("speaker"), node.get("role")), "Source dialogue " + (index + 1)));
            segment.put("transcript", defaultString(firstNonEmpty(node.get("transcript"), node.get("text"), node.get("summary")), ""));
            segment.put("transcriptNodes", List.of(node));
            segment.put("frames", firstNonEmpty(node.get("frames"), frameIdsForWindow(frames, start, end)));
            segment.put("source", "stored_source_transcript");
            segments.add(segment);
            index++;
        }
        return segments;
    }

    private List<Map<String, Object>> transcriptForWindow(List<Map<String, Object>> transcript, double start, double end) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> node : transcript == null ? List.<Map<String, Object>>of() : transcript) {
            double nodeStart = doubleValue(firstNonEmpty(node.get("start"), node.get("sourceStart"), node.get("timelineStart")), 0.0);
            double nodeEnd = Math.max(nodeStart, doubleValue(firstNonEmpty(node.get("end"), node.get("sourceEnd"), node.get("timelineEnd")), nodeStart));
            if (nodeEnd >= start && nodeStart <= end) {
                result.add(node);
            }
        }
        return result;
    }

    private String transcriptText(List<Map<String, Object>> transcript) {
        List<String> parts = new ArrayList<>();
        for (Map<String, Object> node : transcript == null ? List.<Map<String, Object>>of() : transcript) {
            String text = defaultString(firstNonEmpty(node.get("transcript"), node.get("text"), node.get("summary")), "").trim();
            if (!text.isBlank()) {
                parts.add(text);
            }
        }
        return String.join(" ", parts).replaceAll("\\s+", " ").trim();
    }

    private double timelineDuration(List<Map<String, Object>> segments) {
        double max = 0.0;
        for (Map<String, Object> segment : segments == null ? List.<Map<String, Object>>of() : segments) {
            max = Math.max(max, doubleValue(segment.get("timelineEnd"), 0.0));
        }
        return max;
    }

    private Object firstNonEmpty(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            if (value instanceof String text && text.isBlank()) {
                continue;
            }
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private double doubleValue(Object value, Double fallback) {
        double defaultValue = fallback == null ? 0.0 : fallback;
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null || String.valueOf(value).isBlank()) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    private boolean booleanValue(Object value, boolean fallback) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value == null || String.valueOf(value).isBlank()) {
            return fallback;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private boolean restoredFromSourceCache(Map<String, Object> metadata, Map<String, Object> tokenMetadata, Map<String, Object> costMetadata) {
        return booleanValue(metadata == null ? null : metadata.get("restoredFromSourceCache"), false)
                || booleanValue(tokenMetadata == null ? null : tokenMetadata.get("restoredFromSourceCache"), false)
                || booleanValue(costMetadata == null ? null : costMetadata.get("restoredFromSourceCache"), false);
    }

    private double roundSeconds(double value) {
        return Math.round(Math.max(0.0, value) * 1000.0) / 1000.0;
    }

    private void updateShortGraphNodePatch(
            CreatorShortVideo video,
            CreatorShortCandidate candidate,
            Map<String, Object> patch,
            Map<String, Object> metadata,
            Map<String, Object> editDecisionList
    ) {
        Map<String, Object> graph = mutableMap(video.getGraphPayload());
        List<Map<String, Object>> nodes = listOfMaps(graph.get("nodes"));
        String targetNodeId = defaultString(patch.get("graphNodeId"), "short-" + candidate.getId());
        boolean updated = false;
        for (Map<String, Object> node : nodes) {
            boolean sameNode = targetNodeId.equals(stringValue(node.get("id")));
            boolean sameCandidate = candidate.getId().toString().equals(stringValue(node.get("candidateId")));
            if (!sameNode && !sameCandidate) {
                continue;
            }
            node.put("status", candidate.getStatus());
            node.put("reviewStatus", candidate.getReviewStatus());
            node.put("storyIntent", metadata.get("storyIntent"));
            node.put("storyBeatPlan", metadata.get("storyBeatPlan"));
            node.put("hookPlan", metadata.get("hookPlan"));
            node.put("humanPatch", patch);
            node.put("editDecisionList", editDecisionList);
            node.put("manualTimeline", Map.of(
                    "editable", true,
                    "segmentCount", listOfMaps(editDecisionList.get("segments")).size(),
                    "durationSeconds", editDecisionList.get("actualDurationSeconds")
            ));
            node.put("sourceNodeIds", sourceNodeIds(editDecisionList));
            node.put("ui", Map.of(
                    "blink", true,
                    "editable", true,
                    "modal", "SHORT_STORY_GRAPH_NODE_EDITOR",
                    "contextLocked", true,
                    "editableFields", List.of("intent", "beats", "hook", "timeline"),
                    "hasHumanPatch", true
            ));
            updated = true;
        }
        if (updated) {
            graph.put("nodes", nodes);
            graph.put("shortCandidateGraph", Map.of(
                    "source", "creator_short_generation_service",
                    "humanInLoopEnabled", true,
                    "contextLocked", true,
                    "lastHumanEditAt", OffsetDateTime.now().toString()
            ));
            video.setGraphPayload(graph);
        }
    }

    private ShortGenerationResponse runPlanningPipeline(CreatorShortVideo video, CreatorAsset sourceAsset, GenerateShortsRequest request, Path sourceVideoPath, long sourceSizeBytes, String contentType) {
        UUID jobId = video.getGenerationJobId();
        List<Map<String, Object>> trace = new ArrayList<>();
        addTrace(trace, "INGESTION", "COMPLETED", "Source video stored in MinIO and linked as a creator asset.", 10, Map.of(
                "assetId", sourceAsset.getId().toString(),
                "bucket", sourceAsset.getBucket(),
                "objectKey", sourceAsset.getObjectKey()
        ));
        generationJobService.updateGenerationJobProgress(jobId, 15, "Source video uploaded", Map.of("trace", trace));
        logShortStage(video, "INGESTION", logSummary(
                "assetId", sourceAsset.getId(),
                "bucket", sourceAsset.getBucket(),
                "objectKey", sourceAsset.getObjectKey()
        ));
        if (pauseIfRequested(video, "INGESTION", List.of(), Map.of(), trace, Map.of())) {
            return pausedResponse(video);
        }

        CreatorAiService.AiUsageContext usageContext = new CreatorAiService.AiUsageContext(
                video.getTenantId(),
                video.getUserId(),
                video.getProjectId(),
                jobId,
                null
        );
        GoogleShortVideoUnderstandingService.ShortVideoUnderstandingResult understanding = videoUnderstandingService.analyze(video, sourceAsset, sourceVideoPath, sourceSizeBytes, contentType);
        trace.addAll(withoutTraceStage(withoutTraceStage(understanding.trace(), "TRANSCRIPT_CRITIC"), "VIDEO_TYPE_CRITIC"));
        if (understanding.mediaBacked()) {
            creatorAiService.publishProviderUsageDebit(
                    "SHORTS_VIDEO_UNDERSTANDING",
                    understanding.provider(),
                    understanding.model(),
                    understanding.costMetadata(),
                    usageContext,
                    "Google Gemini shorts transcript and video type understanding"
            );
        }
        generationJobService.updateGenerationJobProgress(jobId, 32, "Google transcript and video classification complete", Map.of(
                "activeStage", "VIDEO_TYPE_CLASSIFICATION",
                "mediaBackedUnderstanding", understanding.mediaBacked(),
                "transcript", understanding.transcript(),
                "transcriptGraph", transcriptTimelineGraph(understanding.transcript(), "google_video_understanding"),
                "graph", transcriptTimelineGraph(understanding.transcript(), "google_video_understanding"),
                "videoDna", understanding.videoDna(),
                "transcriptCritic", understanding.transcriptCritic(),
                "videoTypeCritic", understanding.videoTypeCritic(),
                "trace", trace
        ));
        logShortStage(video, "VIDEO_TYPE_CLASSIFICATION", logSummary(
                "mediaBacked", understanding.mediaBacked(),
                "transcriptNodeCount", understanding.transcript().size(),
                "provider", understanding.provider(),
                "model", understanding.model()
        ));
        if (pauseIfRequested(video, "VIDEO_TYPE_CLASSIFICATION", understanding.transcript(), transcriptTimelineGraph(understanding.transcript(), "google_video_understanding"), trace, Map.of(
                "videoDna", understanding.videoDna(),
                "videoTypeCritic", understanding.videoTypeCritic()
        ))) {
            return pausedResponse(video);
        }

        CompletableFuture<ShortSceneAnalysisService.VisualSceneAnalysisResult> sceneVisualPrepFuture = null;
        GoogleShortFullTranscriptWorkerService.FullTranscriptResult fullTranscript = fullTranscriptWorkerService.transcribe(video, sourceAsset, sourceVideoPath);
        trace.addAll(withoutTraceStage(fullTranscript.trace(), "TRANSCRIPT_CRITIC"));
        if (fullTranscript.mediaBacked() && !restoredFromSourceCache(fullTranscript.metadata(), fullTranscript.tokenMetadata(), fullTranscript.costMetadata())) {
            creatorAiService.publishProviderUsageDebit(
                    "SHORTS_FULL_TRANSCRIPT",
                    fullTranscript.provider(),
                    fullTranscript.model(),
                    fullTranscript.costMetadata(),
                    usageContext,
                    "Google Gemini full audio transcript worker for shorts"
            );
        }
        generationJobService.updateGenerationJobProgress(jobId, 45, "Full transcript worker complete", Map.of(
                "activeStage", "TRANSCRIPT",
                "mediaBackedTranscript", fullTranscript.mediaBacked(),
                "transcriptNodeCount", fullTranscript.transcript().size(),
                "transcript", fullTranscript.transcript(),
                "transcriptGraph", transcriptTimelineGraph(fullTranscript.transcript(), "full_transcript_worker"),
                "graph", transcriptTimelineGraph(fullTranscript.transcript(), "full_transcript_worker"),
                "tokenMetadata", fullTranscript.tokenMetadata(),
                "trace", trace
        ));
        persistTranscriptStageSnapshot(
                video,
                "TRANSCRIPT",
                fullTranscript.transcript(),
                transcriptTimelineGraph(fullTranscript.transcript(), "full_transcript_worker"),
                trace,
                logSummary(
                        "mediaBacked", fullTranscript.mediaBacked(),
                        "provider", fullTranscript.provider(),
                        "model", fullTranscript.model(),
                        "restoredFromSourceCache", restoredFromSourceCache(fullTranscript.metadata(), fullTranscript.tokenMetadata(), fullTranscript.costMetadata())
                )
        );
        logShortStage(video, "TRANSCRIPT", logSummary(
                "mediaBacked", fullTranscript.mediaBacked(),
                "transcriptNodeCount", fullTranscript.transcript().size(),
                "provider", fullTranscript.provider(),
                "model", fullTranscript.model()
        ));
        if (pauseIfRequested(video, "TRANSCRIPT", fullTranscript.transcript(), transcriptTimelineGraph(fullTranscript.transcript(), "full_transcript_worker"), trace, Map.of(
                "fullTranscriptWorker", fullTranscript.metadata()
        ))) {
            cancelSceneVisualPrep(sceneVisualPrepFuture, "paused_at_transcript");
            return pausedResponse(video);
        }

        List<Map<String, Object>> transcriptForCritic = fullTranscript.transcript().isEmpty()
                ? new ArrayList<>(understanding.transcript())
                : new ArrayList<>(fullTranscript.transcript());
        TranscriptRepair transcriptRepair = repairTranscriptTimeline(transcriptForCritic);
        transcriptForCritic = transcriptRepair.transcript();
        transcriptForCritic = editedTranscriptOverride(jobId, transcriptForCritic, trace);
        if (transcriptRepair.applied()) {
            addTrace(
                    trace,
                    "TRANSCRIPT_REPAIR",
                    "WARN",
                    "Transcript timeline was normalized before downstream edit decisions.",
                    46,
                    transcriptRepair.metadata()
            );
            logShortStage(video, "TRANSCRIPT_REPAIR", transcriptRepair.metadata());
        }
        GoogleShortTranscriptCriticService.TranscriptCriticResult transcriptCritic = transcriptCriticService.critique(video, sourceAsset, transcriptForCritic, sourceVideoPath);
        trace.addAll(transcriptCritic.trace());
        if (transcriptCritic.mediaBacked() && !restoredFromSourceCache(transcriptCritic.metadata(), transcriptCritic.tokenMetadata(), transcriptCritic.costMetadata())) {
            creatorAiService.publishProviderUsageDebit(
                    "SHORTS_TRANSCRIPT_CRITIC",
                    transcriptCritic.provider(),
                    transcriptCritic.model(),
                    transcriptCritic.costMetadata(),
                    usageContext,
                    "Google Gemini deep transcript critic for shorts"
            );
        }
        generationJobService.updateGenerationJobProgress(jobId, 48, "Deep transcript critic complete", Map.of(
                "activeStage", "TRANSCRIPT_CRITIC",
                "mediaBackedTranscriptCritic", transcriptCritic.mediaBacked(),
                "transcript", transcriptForCritic,
                "transcriptGraph", transcriptTimelineGraph(transcriptForCritic, "deep_transcript_critic_verified"),
                "graph", transcriptTimelineGraph(transcriptForCritic, "deep_transcript_critic_verified"),
                "transcriptCritic", transcriptCritic.metadata(),
                "trace", trace
        ));
        persistTranscriptStageSnapshot(
                video,
                "TRANSCRIPT_CRITIC",
                transcriptForCritic,
                transcriptTimelineGraph(transcriptForCritic, "deep_transcript_critic_verified"),
                trace,
                logSummary(
                        "mediaBacked", transcriptCritic.mediaBacked(),
                        "provider", transcriptCritic.provider(),
                        "model", transcriptCritic.model(),
                        "transcriptCritic", transcriptCritic.metadata(),
                        "restoredFromSourceCache", restoredFromSourceCache(transcriptCritic.metadata(), transcriptCritic.tokenMetadata(), transcriptCritic.costMetadata())
                )
        );
        logShortStage(video, "TRANSCRIPT_CRITIC", logSummary(
                "mediaBacked", transcriptCritic.mediaBacked(),
                "chunkAuditCount", transcriptCritic.chunkAudits().size(),
                "provider", transcriptCritic.provider(),
                "model", transcriptCritic.model()
        ));
        if (pauseIfRequested(video, "TRANSCRIPT_CRITIC", transcriptForCritic, transcriptTimelineGraph(transcriptForCritic, "deep_transcript_critic_verified"), trace, Map.of(
                "transcriptCritic", transcriptCritic.metadata()
        ))) {
            cancelSceneVisualPrep(sceneVisualPrepFuture, "paused_at_transcript_critic");
            return pausedResponse(video);
        }

        GoogleShortVideoTypeCriticService.VideoTypeCriticResult videoTypeCritic = videoTypeCriticService.critique(
                video,
                sourceAsset,
                understanding.videoDna(),
                transcriptForCritic,
                sourceVideoPath
        );
        trace.addAll(videoTypeCritic.trace());
        if (videoTypeCritic.mediaBacked()) {
            creatorAiService.publishProviderUsageDebit(
                    "SHORTS_VIDEO_TYPE_CRITIC",
                    videoTypeCritic.provider(),
                    videoTypeCritic.model(),
                    videoTypeCritic.costMetadata(),
                    usageContext,
                    "Google Gemini full-video type critic for shorts"
            );
        }
        generationJobService.updateGenerationJobProgress(jobId, 52, "Full-video type critic complete", Map.of(
                "activeStage", "VIDEO_TYPE_CRITIC",
                "mediaBackedVideoTypeCritic", videoTypeCritic.mediaBacked(),
                "videoDna", videoTypeCritic.videoDna(),
                "videoTypeCritic", videoTypeCritic.videoTypeCritic(),
                "trace", trace
        ));
        logShortStage(video, "VIDEO_TYPE_CRITIC", logSummary(
                "mediaBacked", videoTypeCritic.mediaBacked(),
                "sampleCount", videoTypeCritic.sampleMetadata().size(),
                "provider", videoTypeCritic.provider(),
                "model", videoTypeCritic.model()
        ));
        if (pauseIfRequested(video, "VIDEO_TYPE_CRITIC", transcriptForCritic, transcriptTimelineGraph(transcriptForCritic, "video_type_critic_verified"), trace, Map.of(
                "videoDna", videoTypeCritic.videoDna(),
                "videoTypeCritic", videoTypeCritic.videoTypeCritic()
        ))) {
            cancelSceneVisualPrep(sceneVisualPrepFuture, "paused_at_video_type_critic");
            return pausedResponse(video);
        }

        ShortsPipelineStrategy pipelineStrategy = determineShortsPipelineStrategy(
                video,
                understanding.videoDna(),
                videoTypeCritic.videoDna(),
                transcriptForCritic,
                sourceSizeBytes
        );
        addTrace(
                trace,
                "PIPELINE_STRATEGY",
                "PASS",
                stringValue(pipelineStrategy.metadata().get("summary")),
                53,
                pipelineStrategy.metadata()
        );
        cancelSceneVisualPrep(sceneVisualPrepFuture, "sequential_video_analysis_" + pipelineStrategy.mode());
        sceneVisualPrepFuture = null;
        generationJobService.updateGenerationJobProgress(jobId, 53, stringValue(pipelineStrategy.metadata().get("summary")), Map.of(
                "activeStage", "VIDEO_TYPE_CRITIC",
                "pipelineStrategy", pipelineStrategy.metadata(),
                "trace", trace
        ));
        logShortStage(video, "PIPELINE_STRATEGY", pipelineStrategy.metadata());

        List<Map<String, Object>> transcriptForScenes = transcriptForCritic.isEmpty()
                ? new ArrayList<>(understanding.transcript())
                : new ArrayList<>(transcriptForCritic);
        generationJobService.updateGenerationJobProgress(jobId, 54, "Analyzing full source video with Gemini sequential video windows", Map.of(
                "activeStage", "VIDEO_ANALYSIS",
                "transcriptNodeCount", transcriptForScenes.size(),
                "sceneAnalysisMode", "sequential_gemini_full_video_scene_map",
                "pipelineStrategy", pipelineStrategy.metadata(),
                "trace", trace
        ));
        long videoAnalysisStartedAt = System.nanoTime();
        ShortSceneAnalysisService.VisualSceneAnalysisResult visualSceneAnalysis = prepareVisualAnalysisGeminiFirst(
                video,
                sourceAsset,
                sourceVideoPath,
                transcriptForScenes,
                "sequential_full_video_pipeline_" + pipelineStrategy.mode()
        );
        long sceneVisualPrepWaitMs = elapsedMillis(videoAnalysisStartedAt);
        boolean fullVideoSceneMap = isFullVideoSceneMapAnalysis(visualSceneAnalysis);
        Map<String, Object> videoAnalysisMetadata = mutableMap(visualSceneAnalysis.metadata());
        videoAnalysisMetadata.put("sequentialProviderCalls", true);
        videoAnalysisMetadata.put("parallelProviderCalls", false);
        videoAnalysisMetadata.put("fallbackUsesFrameExtraction", false);
        videoAnalysisMetadata.put("videoAnalysisMs", sceneVisualPrepWaitMs);
        videoAnalysisMetadata.put("pipelineStrategy", pipelineStrategy.metadata());
        addTrace(
                trace,
                "VIDEO_ANALYSIS",
                fullVideoSceneMap ? "COMPLETED" : "WARN",
                fullVideoSceneMap
                        ? "Gemini analyzed the source video through whole-video/10-minute sequential windows; backend frame-by-frame analysis was skipped."
                        : "Gemini full-video analysis did not return usable scene windows; pipeline will use transcript windows without backend frame extraction.",
                55,
                videoAnalysisMetadata
        );

        ShortSceneAnalysisService.SceneAnalysisResult sceneAnalysis;
        if (fullVideoSceneMap) {
            sceneAnalysis = sceneAnalysisService.attachTranscript(video, visualSceneAnalysis, transcriptForScenes);
            Map<String, Object> sceneAnalysisMetadata = mutableMap(sceneAnalysis.metadata());
            sceneAnalysisMetadata.put("visualPrepWaitMs", sceneVisualPrepWaitMs);
            sceneAnalysisMetadata.put("videoAnalysisMs", sceneVisualPrepWaitMs);
            sceneAnalysisMetadata.put("videoAnalysisMode", "sequential_gemini_full_video_scene_map");
            sceneAnalysisMetadata.put("visualPrepCompletedBeforeSceneStage", false);
            sceneAnalysisMetadata.put("frameExtractionSkipped", true);
            sceneAnalysisMetadata.put("pipelineStrategy", pipelineStrategy.metadata());
            sceneAnalysis = new ShortSceneAnalysisService.SceneAnalysisResult(
                    sceneAnalysis.mediaBacked(),
                    sceneAnalysis.scenes(),
                    sceneAnalysis.frames(),
                    sceneAnalysis.transcript(),
                    sceneAnalysis.trace(),
                    sceneAnalysisMetadata
            );
        } else {
            trace.addAll(withoutTraceStage(withoutTraceStage(visualSceneAnalysis.trace(), "SCENE_CRITIC"), "VIDEO_ANALYSIS"));
            sceneAnalysis = transcriptFirstSceneAnalysis(video, transcriptForScenes, pipelineStrategy);
            Map<String, Object> sceneAnalysisMetadata = mutableMap(sceneAnalysis.metadata());
            sceneAnalysisMetadata.put("videoAnalysisFallback", videoAnalysisMetadata);
            sceneAnalysisMetadata.put("videoAnalysisMode", "transcript_windows_after_full_video_analysis_warning");
            sceneAnalysisMetadata.put("frameExtractionSkipped", true);
            sceneAnalysisMetadata.put("pipelineStrategy", pipelineStrategy.metadata());
            sceneAnalysis = new ShortSceneAnalysisService.SceneAnalysisResult(
                    sceneAnalysis.mediaBacked(),
                    sceneAnalysis.scenes(),
                    sceneAnalysis.frames(),
                    sceneAnalysis.transcript(),
                    sceneAnalysis.trace(),
                    sceneAnalysisMetadata
            );
        }
        trace.addAll(withoutTraceStage(sceneAnalysis.trace(), "SCENE_CRITIC"));
        Map<String, Object> sceneTimeline = sceneTimeline(
                sceneAnalysis.transcript(),
                sceneAnalysis.scenes(),
                sceneAnalysis.frames(),
                Map.of(),
                Map.of()
        );
        generationJobService.updateGenerationJobProgress(jobId, 56, "Scene analysis complete", Map.of(
                "activeStage", "SCENE_ANALYSIS",
                "mediaBackedScenes", sceneAnalysis.mediaBacked(),
                "sceneCount", sceneAnalysis.scenes().size(),
                "frameCount", sceneAnalysis.frames().size(),
                "transcript", sceneAnalysis.transcript(),
                "sceneTimeline", sceneTimeline,
                "sceneAnalysis", Map.of(
                        "mediaBacked", sceneAnalysis.mediaBacked(),
                        "metadata", sceneAnalysis.metadata(),
                        "scenes", sceneAnalysis.scenes(),
                        "frames", sceneAnalysis.frames()
                ),
                "trace", trace
        ));
        persistSceneTimelineStageSnapshot(
                video,
                "SCENE_ANALYSIS",
                sceneAnalysis,
                sceneTimeline,
                Map.of(),
                trace,
                logSummary(
                        "pipelineStrategy", pipelineStrategy.metadata(),
                        "sceneAnalysisMode", pipelineStrategy.sceneAnalysisMode()
                )
        );
        logShortStage(video, "SCENE_ANALYSIS", logSummary(
                "mediaBacked", sceneAnalysis.mediaBacked(),
                "sceneCount", sceneAnalysis.scenes().size(),
                "frameCount", sceneAnalysis.frames().size()
        ));
        if (pauseIfRequested(video, "SCENE_ANALYSIS", sceneAnalysis.transcript(), transcriptTimelineGraph(sceneAnalysis.transcript(), "scene_analysis"), trace, Map.of(
                "scenes", sceneAnalysis.scenes(),
                "frames", sceneAnalysis.frames(),
                "sceneTimeline", sceneTimeline
        ))) {
            return pausedResponse(video);
        }

        Map<String, Object> mediaVideoDna = videoTypeCritic.videoDna().isEmpty()
                ? (understanding.videoDna().isEmpty() ? fallbackVideoDna(video) : mutableMap(understanding.videoDna()))
                : mutableMap(videoTypeCritic.videoDna());

        generationJobService.updateGenerationJobProgress(jobId, 58, pipelineStrategy.sceneCriticStageMessage(), Map.of(
                "activeStage", "SCENE_CRITIC",
                "mediaBackedScenes", sceneAnalysis.mediaBacked(),
                "sceneCount", sceneAnalysis.scenes().size(),
                "frameCount", sceneAnalysis.frames().size(),
                "transcriptNodeCount", sceneAnalysis.transcript().size(),
                "sceneCriticStatus", isFullVideoSceneMapAnalysis(sceneAnalysis) ? "REUSED_FULL_VIDEO_SCENE_MAP" : "DETERMINISTIC_FRAME_FREE_FALLBACK",
                "pipelineStrategy", pipelineStrategy.metadata(),
                "trace", trace
        ));

        GoogleShortSceneCriticService.SceneCriticResult sceneCritic = isFullVideoSceneMapAnalysis(sceneAnalysis)
                ? sceneMapBackedSceneCritic(video, sceneAnalysis, pipelineStrategy)
                : transcriptFirstSceneCritic(video, sceneAnalysis, pipelineStrategy);
        trace.addAll(sceneCritic.trace());
        SceneEvidenceRepair sceneEvidenceRepair = repairSceneEvidenceIfUnsafe(video, sceneAnalysis, sceneCritic, transcriptForScenes);
        if (sceneEvidenceRepair.applied()) {
            sceneAnalysis = sceneEvidenceRepair.sceneAnalysis();
            sceneCritic = withSceneEvidenceRepair(sceneCritic, sceneEvidenceRepair);
            sceneTimeline = sceneTimeline(
                    sceneAnalysis.transcript(),
                    sceneAnalysis.scenes(),
                    sceneAnalysis.frames(),
                    sceneCritic.sceneCritic(),
                    Map.of()
            );
            addTrace(
                    trace,
                    "SCENE_REPAIR",
                    "WARN",
                    "Scene evidence was unsafe, so conservative transcript-led scene windows were generated.",
                    61,
                    sceneEvidenceRepair.metadata()
            );
            generationJobService.updateGenerationJobProgress(jobId, 61, "Unsafe scene map repaired with conservative windows", Map.of(
                    "activeStage", "SCENE_REPAIR",
                    "sceneRepair", sceneEvidenceRepair.metadata(),
                    "transcript", sceneAnalysis.transcript(),
                    "sceneTimeline", sceneTimeline,
                    "sceneAnalysis", Map.of(
                            "mediaBacked", sceneAnalysis.mediaBacked(),
                            "metadata", sceneAnalysis.metadata(),
                            "scenes", sceneAnalysis.scenes(),
                            "frames", sceneAnalysis.frames()
                    ),
                    "trace", trace
            ));
            persistSceneTimelineStageSnapshot(
                    video,
                    "SCENE_REPAIR",
                    sceneAnalysis,
                    sceneTimeline,
                    sceneCritic.sceneCritic(),
                    trace,
                    logSummary("sceneRepair", sceneEvidenceRepair.metadata())
            );
            logShortStage(video, "SCENE_REPAIR", sceneEvidenceRepair.metadata());
            if (pauseIfRequested(video, "SCENE_REPAIR", sceneAnalysis.transcript(), transcriptTimelineGraph(sceneAnalysis.transcript(), "scene_evidence_repair"), trace, Map.of(
                    "sceneRepair", sceneEvidenceRepair.metadata(),
                    "scenes", sceneAnalysis.scenes(),
                    "frames", sceneAnalysis.frames(),
                    "sceneTimeline", sceneTimeline
            ))) {
                return pausedResponse(video);
            }
        }
        if (sceneCritic.mediaBacked()
                && !isSceneMapBackedSceneCritic(sceneCritic)
                && !restoredFromSourceCache(sceneCritic.metadata(), sceneCritic.tokenMetadata(), sceneCritic.costMetadata())) {
            creatorAiService.publishProviderUsageDebit(
                    "SHORTS_SCENE_CRITIC",
                    sceneCritic.provider(),
                    sceneCritic.model(),
                    sceneCritic.costMetadata(),
                    usageContext,
                    "Google Gemini deep vision scene critic for shorts"
            );
        }
        sceneTimeline = sceneTimeline(
                sceneAnalysis.transcript(),
                sceneAnalysis.scenes(),
                sceneAnalysis.frames(),
                sceneCritic.sceneCritic(),
                Map.of()
        );
        generationJobService.updateGenerationJobProgress(jobId, 60, "Deep scene critic complete", Map.of(
                "activeStage", "SCENE_CRITIC",
                "mediaBackedSceneCritic", sceneCritic.mediaBacked(),
                "sceneCritic", sceneCritic.sceneCritic(),
                "pipelineStrategy", pipelineStrategy.metadata(),
                "sceneTimeline", sceneTimeline,
                "trace", trace
        ));
        persistSceneTimelineStageSnapshot(
                video,
                "SCENE_CRITIC",
                sceneAnalysis,
                sceneTimeline,
                sceneCritic.sceneCritic(),
                trace,
                logSummary(
                        "mediaBacked", sceneCritic.mediaBacked(),
                        "provider", sceneCritic.provider(),
                        "model", sceneCritic.model(),
                        "restoredFromSourceCache", restoredFromSourceCache(sceneCritic.metadata(), sceneCritic.tokenMetadata(), sceneCritic.costMetadata())
                )
        );
        logShortStage(video, "SCENE_CRITIC", logSummary(
                "mediaBacked", sceneCritic.mediaBacked(),
                "provider", sceneCritic.provider(),
                "model", sceneCritic.model()
        ));
        if (pauseIfRequested(video, "SCENE_CRITIC", sceneAnalysis.transcript(), transcriptTimelineGraph(sceneAnalysis.transcript(), "scene_critic_verified"), trace, Map.of(
                "sceneCritic", sceneCritic.sceneCritic(),
                "scenes", sceneAnalysis.scenes(),
                "frames", sceneAnalysis.frames(),
                "sceneTimeline", sceneTimeline
        ))) {
            return pausedResponse(video);
        }

        ShortVideoGraphBuilderService.GraphBuildResult graphBuild = graphBuilderService.build(
                video,
                mediaVideoDna,
                sceneAnalysis.transcript(),
                sceneAnalysis.scenes()
        );
        trace.addAll(graphBuild.trace());
        graphBuild = editedGraphOverride(jobId, graphBuild, sceneAnalysis.transcript(), trace);
        generationJobService.updateGenerationJobProgress(jobId, 64, "Real video graph built", Map.of(
                "activeStage", "VIDEO_GRAPH_BUILDER",
                "transcriptNodeCount", sceneAnalysis.transcript().size(),
                "graphNodeCount", listOfMaps(graphBuild.graph().get("nodes")).size(),
                "graphEdgeCount", listOfMaps(graphBuild.graph().get("edges")).size(),
                "transcriptPreview", transcriptPreviewForJob(sceneAnalysis.transcript(), "scene_aligned_transcript"),
                "graphPreview", graphPreviewForJob(graphBuild.graph()),
                "graphBuild", graphBuild.metadata(),
                "trace", trace
        ));
        logShortStage(video, "VIDEO_GRAPH_BUILDER", logSummary(
                "nodeCount", listOfMaps(graphBuild.graph().get("nodes")).size(),
                "edgeCount", listOfMaps(graphBuild.graph().get("edges")).size()
        ));
        if (pauseIfRequested(video, "VIDEO_GRAPH_BUILDER", sceneAnalysis.transcript(), graphBuild.graph(), trace, Map.of(
                "graphBuild", graphBuild.metadata()
        ))) {
            return pausedResponse(video);
        }

        GoogleShortStoryCriticService.StoryCriticResult storyCritic = storyCriticService.critique(
                video,
                mediaVideoDna,
                sceneAnalysis.transcript(),
                graphBuild.graph(),
                sceneAnalysis.scenes(),
                transcriptCritic.metadata(),
                videoTypeCritic.videoTypeCritic(),
                sceneCritic.sceneCritic()
        );
        trace.addAll(storyCritic.trace());
        StoryEvidenceRepair storyEvidenceRepair = repairStoryEvidenceIfWeak(video, sceneAnalysis.transcript(), graphBuild.graph(), storyCritic);
        if (storyEvidenceRepair.applied()) {
            storyCritic = withStoryEvidenceRepair(storyCritic, storyEvidenceRepair);
            addTrace(
                    trace,
                    "STORY_REPAIR",
                    "WARN",
                    "Story confidence was weak, so deterministic graph-backed preservation rules were added.",
                    65,
                    storyEvidenceRepair.metadata()
            );
            logShortStage(video, "STORY_REPAIR", storyEvidenceRepair.metadata());
        }
        if (storyCritic.mediaBacked()) {
            creatorAiService.publishProviderUsageDebit(
                    "SHORTS_STORY_CRITIC",
                    storyCritic.provider(),
                    storyCritic.model(),
                    storyCritic.costMetadata(),
                    usageContext,
                    "Google Gemini independent story critic for shorts"
            );
        }
        generationJobService.updateGenerationJobProgress(jobId, 65, "Independent story critic complete", Map.of(
                "activeStage", "STORY_CRITIC",
                "mediaBackedStoryCritic", storyCritic.mediaBacked(),
                "storyUnderstanding", storyCritic.storyUnderstanding(),
                "storyCritic", storyCritic.storyCritic(),
                "trace", trace
        ));
        logShortStage(video, "STORY_CRITIC", logSummary(
                "mediaBacked", storyCritic.mediaBacked(),
                "provider", storyCritic.provider(),
                "model", storyCritic.model()
        ));
        if (pauseIfRequested(video, "STORY_CRITIC", sceneAnalysis.transcript(), graphBuild.graph(), trace, Map.of(
                "storyUnderstanding", storyCritic.storyUnderstanding(),
                "storyCritic", storyCritic.storyCritic()
        ))) {
            return pausedResponse(video);
        }

        ShortInterestingnessScoringService.InterestingnessScoringResult interestingnessScoring = interestingnessScoringService.score(
                video,
                mediaVideoDna,
                sceneAnalysis.transcript(),
                graphBuild.graph(),
                sceneAnalysis.scenes(),
                sceneCritic.sceneCritic(),
                storyCritic.storyUnderstanding(),
                storyCritic.storyCritic()
        );
        trace.addAll(withoutTraceStage(interestingnessScoring.trace(), "INTERESTINGNESS_CRITIC"));
        generationJobService.updateGenerationJobProgress(jobId, 66, "Robust interestingness scoring complete", Map.of(
                "activeStage", "INTERESTINGNESS",
                "evidenceBackedInterestingness", interestingnessScoring.evidenceBacked(),
                "topMoments", interestingnessScoring.topMoments(),
                "candidateWindows", interestingnessScoring.candidateWindows(),
                "trace", trace
        ));
        logShortStage(video, "INTERESTINGNESS", logSummary(
                "evidenceBacked", interestingnessScoring.evidenceBacked(),
                "topMomentCount", interestingnessScoring.topMoments().size(),
                "candidateWindowCount", interestingnessScoring.candidateWindows().size()
        ));
        if (pauseIfRequested(video, "INTERESTINGNESS", sceneAnalysis.transcript(), graphBuild.graph(), trace, Map.of(
                "topMoments", interestingnessScoring.topMoments(),
                "candidateWindows", interestingnessScoring.candidateWindows()
        ))) {
            return pausedResponse(video);
        }

        ShortInterestingnessCriticService.InterestingnessCriticResult interestingnessCritic = interestingnessCriticService.critique(
                video,
                mediaVideoDna,
                interestingnessScoring,
                graphBuild.graph(),
                sceneCritic.sceneCritic(),
                storyCritic.storyCritic()
        );
        trace.addAll(interestingnessCritic.trace());
        generationJobService.updateGenerationJobProgress(jobId, 67, "Independent interestingness critic complete", Map.of(
                "activeStage", "INTERESTINGNESS_CRITIC",
                "interestingnessCritic", interestingnessCritic.interestingnessCritic(),
                "trace", trace
        ));
        logShortStage(video, "INTERESTINGNESS_CRITIC", logSummary(
                "metadata", interestingnessCritic.metadata()
        ));
        if (pauseIfRequested(video, "INTERESTINGNESS_CRITIC", sceneAnalysis.transcript(), graphBuild.graph(), trace, Map.of(
                "interestingnessCritic", interestingnessCritic.interestingnessCritic()
        ))) {
            return pausedResponse(video);
        }
        List<Map<String, Object>> scoredTranscriptForPlanning = interestingnessScoring.scoredTranscript().isEmpty()
                ? new ArrayList<>(sceneAnalysis.transcript())
                : new ArrayList<>(interestingnessScoring.scoredTranscript());

        Map<String, Object> aiInput = buildAiInput(video, sourceAsset, request, understanding, fullTranscript, transcriptCritic, videoTypeCritic, sceneAnalysis, sceneCritic, storyCritic, interestingnessScoring, interestingnessCritic, graphBuild);
        Map<String, Object> humanProcessingEditsForPlanning = humanProcessingEditPayload(jobId);
        if (!humanProcessingEditsForPlanning.isEmpty()) {
            aiInput.put("humanProcessingEdits", humanProcessingEditsForPlanning);
        }
        generationJobService.updateGenerationJobProgress(jobId, 68, "Running shorts intelligence agents", Map.of(
                "activeStage", "STORY_UNDERSTANDING",
                "trace", trace
        ));
        logShortStage(video, "STORY_UNDERSTANDING", logSummary("status", "AI_PLANNER_STARTED"));
        if (pauseIfRequested(video, "STORY_UNDERSTANDING", scoredTranscriptForPlanning, graphBuild.graph(), trace, Map.of(
                "humanProcessingEdits", humanProcessingEditsForPlanning
        ))) {
            return pausedResponse(video);
        }
        CreatorAiService.MeteredAiResponse aiResponse = creatorAiService.generateMetered(JOB_TYPE, aiInput, usageContext);
        creatorAiService.publishBillingDebit(JOB_TYPE, aiResponse, usageContext);

        Map<String, Object> aiOutput = aiResponse.output() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(aiResponse.output());
        Map<String, Object> videoDna = mapValue(aiOutput.get("videoDna"));
        if (videoDna.isEmpty() || "upload_metadata".equalsIgnoreCase(stringValue(videoDna.get("analysisSource")))) {
            videoDna = mediaVideoDna.isEmpty() ? fallbackVideoDna(video) : mutableMap(mediaVideoDna);
        }
        List<Map<String, Object>> realTranscript = !scoredTranscriptForPlanning.isEmpty()
                ? new ArrayList<>(scoredTranscriptForPlanning)
                : (!fullTranscript.transcript().isEmpty() ? new ArrayList<>(fullTranscript.transcript()) : new ArrayList<>(understanding.transcript()));
        List<Map<String, Object>> transcript = realTranscript.isEmpty() ? listOfMaps(aiOutput.get("transcript")) : realTranscript;
        if (transcript.isEmpty()) {
            transcript = fallbackTranscript(video);
        }
        Map<String, Object> graph = graphBuild.graph().isEmpty() ? mapValue(aiOutput.get("graph")) : mutableMap(graphBuild.graph());
        if (graph.isEmpty()) {
            graph = fallbackGraph(transcript, videoDna);
        }
        if (!humanProcessingEditsForPlanning.isEmpty()) {
            graph.put("humanProcessingEdits", humanProcessingEditsForPlanning);
        }
        List<Map<String, Object>> aiTrace = listOfMaps(aiOutput.get("trace"));
        aiTrace = withoutTraceStage(aiTrace, "STORY_UNDERSTANDING");
        aiTrace = withoutTraceStage(aiTrace, "STORY_CRITIC");
        aiTrace = withoutTraceStage(aiTrace, "INTERESTINGNESS");
        aiTrace = withoutTraceStage(aiTrace, "INTERESTINGNESS_CRITIC");
        aiTrace = withoutTraceStage(aiTrace, "STORY_BEAT_PLANNING");
        aiTrace = withoutTraceStage(aiTrace, "VISUAL_STORY_COMPOSITION");
        aiTrace = withoutTraceStage(aiTrace, "HOOK_GENERATION");
        aiTrace = withoutTraceStage(aiTrace, "VISUAL_ENHANCEMENT");
        aiTrace = withoutTraceStage(aiTrace, "VISUAL_CRITIC");
        aiTrace = withoutTraceStage(aiTrace, "CONTINUITY_CRITIC");
        aiTrace = withoutTraceStage(aiTrace, "GLOBAL_CRITIC");
        trace.addAll(normalizeTrace(aiTrace));

        List<Map<String, Object>> candidatePayloads = listOfMaps(aiOutput.get("candidates"));
        if (candidatePayloads.isEmpty()) {
            candidatePayloads = fallbackCandidates(video, transcript, graph);
        }
        logShortStage(video, "STORY_UNDERSTANDING", logSummary(
                "status", "AI_PLANNER_COMPLETED",
                "candidatePayloadCount", candidatePayloads.size(),
                "provider", aiOutput.get("provider"),
                "model", aiOutput.get("model")
        ));
        generationJobService.updateGenerationJobProgress(jobId, 69, "Shorts intelligence agents complete", Map.of(
                "activeStage", "STORY_UNDERSTANDING",
                "videoDna", videoDna,
                "transcript", transcript,
                "graph", graph,
                "candidatePayloads", candidatePayloads,
                "trace", trace
        ));
        if (pauseIfRequested(video, "STORY_UNDERSTANDING", transcript, graph, trace, Map.of(
                "candidatePayloads", candidatePayloads,
                "humanProcessingEdits", humanProcessingEditsForPlanning
        ))) {
            return pausedResponse(video);
        }

        ShortStoryBeatPlanningService.StoryBeatPlanningResult storyBeatPlanning = storyBeatPlanningService.plan(
                video,
                videoDna,
                transcript,
                graph,
                sceneAnalysis.scenes(),
                storyCritic.storyUnderstanding(),
                storyCritic.storyCritic(),
                interestingnessScoring,
                interestingnessCritic,
                candidatePayloads
        );
        candidatePayloads = storyBeatPlanning.candidates();
        trace.addAll(storyBeatPlanning.trace());
        generationJobService.updateGenerationJobProgress(jobId, 70, "Story beat intents planned", Map.of(
                "activeStage", "STORY_BEAT_PLANNING",
                "storyBeatPlanning", storyBeatPlanning.metadata(),
                "intentCount", storyBeatPlanning.intents().size(),
                "planCount", storyBeatPlanning.plans().size(),
                "candidateCount", candidatePayloads.size(),
                "trace", trace
        ));
        logShortStage(video, "STORY_BEAT_PLANNING", logSummary(
                "intentCount", storyBeatPlanning.intents().size(),
                "planCount", storyBeatPlanning.plans().size(),
                "candidateCount", candidatePayloads.size()
        ));
        if (pauseIfRequested(video, "STORY_BEAT_PLANNING", transcript, graph, trace, Map.of(
                "storyBeatPlanning", storyBeatPlanning.metadata(),
                "storyBeatPlans", storyBeatPlanning.plans(),
                "storyBeatIntents", storyBeatPlanning.intents(),
                "candidatePayloads", candidatePayloads
        ))) {
            return pausedResponse(video);
        }

        ShortVisualStoryCompositionService.VisualStoryCompositionResult visualStoryComposition = visualStoryCompositionService.compose(
                video,
                videoDna,
                transcript,
                graph,
                sceneAnalysis.scenes(),
                sceneCritic.sceneCritic(),
                candidatePayloads,
                storyBeatPlanning.plans()
        );
        candidatePayloads = visualStoryComposition.candidates();
        trace.addAll(visualStoryComposition.trace());
        generationJobService.updateGenerationJobProgress(jobId, 72, "Visual story composition planned", Map.of(
                "activeStage", "VISUAL_STORY_COMPOSITION",
                "visualStoryComposition", visualStoryComposition.metadata(),
                "candidateCount", candidatePayloads.size(),
                "planCount", visualStoryComposition.plans().size(),
                "trace", trace
        ));
        logShortStage(video, "VISUAL_STORY_COMPOSITION", logSummary(
                "planCount", visualStoryComposition.plans().size(),
                "candidateCount", candidatePayloads.size()
        ));
        if (pauseIfRequested(video, "VISUAL_STORY_COMPOSITION", transcript, graph, trace, Map.of(
                "visualStoryComposition", visualStoryComposition.metadata(),
                "visualStoryPlans", visualStoryComposition.plans(),
                "candidatePayloads", candidatePayloads
        ))) {
            return pausedResponse(video);
        }

        ShortCompressionPlannerService.CompressionPlanningResult compressionPlanning = compressionPlannerService.plan(video, transcript, graph, interestingnessScoring, interestingnessCritic);
        List<Map<String, Object>> compressionPlans = new ArrayList<>();
        compressionPlans.addAll(visualStoryComposition.plans());
        compressionPlans.addAll(compressionPlanning.plans());
        trace.addAll(compressionPlanning.trace());
        generationJobService.updateGenerationJobProgress(jobId, 74, "Real compression plans generated", Map.of(
                "activeStage", "COMPRESSION",
                "planCount", compressionPlans.size(),
                "storyBeatPlanCount", storyBeatPlanning.plans().size(),
                "visualCompositionPlanCount", visualStoryComposition.plans().size(),
                "compressionWorkerPlanCount", compressionPlanning.plans().size(),
                "trace", trace
        ));
        logShortStage(video, "COMPRESSION", logSummary(
                "planCount", compressionPlans.size(),
                "compressionWorkerPlanCount", compressionPlanning.plans().size()
        ));
        if (pauseIfRequested(video, "COMPRESSION", transcript, graph, trace, Map.of(
                "compressionPlans", compressionPlans,
                "compressionPlanning", compressionPlanning.metadata(),
                "candidatePayloads", candidatePayloads
        ))) {
            return pausedResponse(video);
        }

        ShortHookGenerationService.HookGenerationResult hookGeneration = hookGenerationService.generate(
                video,
                videoDna,
                transcript,
                graph,
                compressionPlans,
                candidatePayloads,
                storyCritic.storyCritic(),
                interestingnessCritic.interestingnessCritic()
        );
        candidatePayloads = hookGeneration.candidates();
        trace.addAll(hookGeneration.trace());
        generationJobService.updateGenerationJobProgress(jobId, 78, "Production hook generation complete", Map.of(
                "activeStage", "HOOK_GENERATION",
                "hookCount", hookGeneration.hooks().size(),
                "hookGeneration", hookGeneration.metadata(),
                "trace", trace
        ));
        logShortStage(video, "HOOK_GENERATION", logSummary(
                "hookCount", hookGeneration.hooks().size(),
                "candidateCount", candidatePayloads.size()
        ));
        if (pauseIfRequested(video, "HOOK_GENERATION", transcript, graph, trace, Map.of(
                "hookGeneration", hookGeneration.metadata(),
                "hooks", hookGeneration.hooks(),
                "candidatePayloads", candidatePayloads
        ))) {
            return pausedResponse(video);
        }

        ShortCandidateCriticRepairService.CandidateCriticRepairResult criticRepair = null;
        List<Map<String, Object>> criticRepairTrace = new ArrayList<>();
        int criticRepairAttempts = 0;
        for (int attempt = 1; attempt <= 3; attempt++) {
            criticRepairAttempts = attempt;
            criticRepair = candidateCriticRepairService.repair(
                    video,
                    videoDna,
                    transcript,
                    graph,
                    candidatePayloads,
                    compressionPlans
            );
            candidatePayloads = criticRepair.candidates();
            List<Map<String, Object>> attemptTrace = withRetryAttempt(
                    withoutTraceStage(withoutTraceStage(criticRepair.trace(), "CONTINUITY_CRITIC"), "GLOBAL_CRITIC"),
                    attempt
            );
            criticRepairTrace.addAll(attemptTrace);
            if (!needsAnotherRepairPass(candidatePayloads)) {
                break;
            }
        }
        trace.addAll(criticRepairTrace);
        generationJobService.updateGenerationJobProgress(jobId, 82, "Short candidates compressed, critiqued, and repaired", Map.of(
                "videoDna", videoDna,
                "graph", graph,
                "compressionPlanCount", compressionPlans.size(),
                "storyBeatPlanCount", storyBeatPlanning.plans().size(),
                "visualCompositionPlanCount", visualStoryComposition.plans().size(),
                "criticRepairAttempts", criticRepairAttempts,
                "candidateCount", candidatePayloads.size(),
                "trace", trace
        ));
        logShortStage(video, "TARGETED_REPAIR", logSummary(
                "criticRepairAttempts", criticRepairAttempts,
                "candidateCount", candidatePayloads.size(),
                "metadata", criticRepair == null ? Map.of() : criticRepair.metadata()
        ));
        if (pauseIfRequested(video, "TARGETED_REPAIR", transcript, graph, trace, Map.of(
                "criticRepairAttempts", criticRepairAttempts,
                "criticRepair", criticRepair == null ? Map.of() : criticRepair.metadata(),
                "candidatePayloads", candidatePayloads
        ))) {
            return pausedResponse(video);
        }

        ShortVisualEnhancementService.VisualEnhancementResult visualEnhancement = visualEnhancementService.enhance(
                video,
                videoDna,
                transcript,
                graph,
                candidatePayloads,
                sceneAnalysis.scenes(),
                sceneAnalysis.frames(),
                sceneCritic.sceneCritic()
        );
        candidatePayloads = visualEnhancement.candidates();
        trace.addAll(visualEnhancement.trace());
        generationJobService.updateGenerationJobProgress(jobId, 86, "Visual enhancement plans generated", Map.of(
                "activeStage", "VISUAL_ENHANCEMENT",
                "visualEnhancement", visualEnhancement.metadata(),
                "candidateCount", candidatePayloads.size(),
                "trace", trace
        ));
        logShortStage(video, "VISUAL_ENHANCEMENT", logSummary(
                "candidateCount", candidatePayloads.size(),
                "metadata", visualEnhancement.metadata()
        ));
        if (pauseIfRequested(video, "VISUAL_ENHANCEMENT", transcript, graph, trace, Map.of(
                "visualEnhancement", visualEnhancement.metadata(),
                "candidatePayloads", candidatePayloads
        ))) {
            return pausedResponse(video);
        }

        ShortVisualCriticService.VisualCriticResult visualCritic = visualCriticService.critique(
                video,
                videoDna,
                candidatePayloads,
                sceneAnalysis.scenes(),
                sceneAnalysis.frames(),
                sceneCritic.sceneCritic()
        );
        candidatePayloads = visualCritic.candidates();
        trace.addAll(visualCritic.trace());
        generationJobService.updateGenerationJobProgress(jobId, 88, "Visual critic verified render plans", Map.of(
                "activeStage", "VISUAL_CRITIC",
                "visualCritic", visualCritic.metadata(),
                "candidateCount", candidatePayloads.size(),
                "trace", trace
        ));
        logShortStage(video, "VISUAL_CRITIC", logSummary(
                "candidateCount", candidatePayloads.size(),
                "metadata", visualCritic.metadata()
        ));
        if (pauseIfRequested(video, "VISUAL_CRITIC", transcript, graph, trace, Map.of(
                "visualCritic", visualCritic.metadata(),
                "candidatePayloads", candidatePayloads
        ))) {
            return pausedResponse(video);
        }

        ShortContinuityCriticService.ContinuityCriticResult continuityCritic = continuityCriticService.critique(
                video,
                videoDna,
                transcript,
                graph,
                candidatePayloads,
                sceneAnalysis.scenes(),
                storyCritic.storyCritic(),
                sceneCritic.sceneCritic()
        );
        candidatePayloads = continuityCritic.candidates();
        trace.addAll(continuityCritic.trace());
        Map<String, Object> continuitySceneTimeline = sceneTimeline(
                transcript,
                sceneAnalysis.scenes(),
                sceneAnalysis.frames(),
                sceneCritic.sceneCritic(),
                continuityCritic.metadata()
        );
        generationJobService.updateGenerationJobProgress(jobId, 90, "Continuity critic verified final edit continuity", Map.of(
                "activeStage", "CONTINUITY_CRITIC",
                "continuityCritic", continuityCritic.metadata(),
                "sceneTimeline", continuitySceneTimeline,
                "candidateCount", candidatePayloads.size(),
                "trace", trace
        ));
        persistSceneTimelineStageSnapshot(
                video,
                "CONTINUITY_CRITIC",
                sceneAnalysis,
                continuitySceneTimeline,
                sceneCritic.sceneCritic(),
                trace,
                logSummary("continuityCritic", continuityCritic.metadata())
        );
        logShortStage(video, "CONTINUITY_CRITIC", logSummary(
                "candidateCount", candidatePayloads.size(),
                "metadata", continuityCritic.metadata()
        ));
        if (pauseIfRequested(video, "CONTINUITY_CRITIC", transcript, graph, trace, Map.of(
                "continuityCritic", continuityCritic.metadata(),
                "sceneTimeline", continuitySceneTimeline,
                "candidatePayloads", candidatePayloads
        ))) {
            return pausedResponse(video);
        }

        ShortCandidateRankingService.CandidateRankingResult candidateRanking = candidateRankingService.rank(
                video,
                videoDna,
                transcript,
                graph,
                candidatePayloads
        );
        candidatePayloads = candidateRanking.candidates();
        trace.addAll(candidateRanking.trace());
        generationJobService.updateGenerationJobProgress(jobId, 91, "Candidates ranked for final readiness", Map.of(
                "activeStage", "CANDIDATE_RANKING",
                "candidateRanking", candidateRanking.metadata(),
                "candidateCount", candidatePayloads.size(),
                "trace", trace
        ));
        logShortStage(video, "CANDIDATE_RANKING", logSummary(
                "candidateCount", candidatePayloads.size(),
                "metadata", candidateRanking.metadata()
        ));
        if (pauseIfRequested(video, "CANDIDATE_RANKING", transcript, graph, trace, Map.of(
                "candidateRanking", candidateRanking.metadata(),
                "candidatePayloads", candidatePayloads
        ))) {
            return pausedResponse(video);
        }

        Map<String, Object> globalCriticEvidence = new LinkedHashMap<>();
        globalCriticEvidence.put("transcriptCritic", transcriptCritic.metadata());
        globalCriticEvidence.put("videoTypeCritic", videoTypeCritic.metadata());
        globalCriticEvidence.put("storyCritic", storyCritic.metadata());
        globalCriticEvidence.put("interestingnessCritic", interestingnessCritic.metadata());
        globalCriticEvidence.put("storyBeatPlanning", storyBeatPlanning.metadata());
        globalCriticEvidence.put("visualStoryComposition", visualStoryComposition.metadata());
        globalCriticEvidence.put("compressionPlanner", compressionPlanning.metadata());
        globalCriticEvidence.put("candidateRanking", candidateRanking.metadata());
        globalCriticEvidence.put("hookGeneration", hookGeneration.metadata());
        globalCriticEvidence.put("criticRepair", criticRepair.metadata());
        globalCriticEvidence.put("visualEnhancement", visualEnhancement.metadata());
        globalCriticEvidence.put("visualCritic", visualCritic.metadata());
        globalCriticEvidence.put("continuityCritic", continuityCritic.metadata());
        ShortGlobalCriticService.GlobalCriticResult globalCritic = globalCriticService.critique(
                video,
                videoDna,
                transcript,
                graph,
                candidatePayloads,
                globalCriticEvidence
        );
        candidatePayloads = globalCritic.candidates();
        trace.addAll(globalCritic.trace());
        generationJobService.updateGenerationJobProgress(jobId, 92, "Global critic verified production readiness", Map.of(
                "activeStage", "GLOBAL_CRITIC",
                "globalCritic", globalCritic.metadata(),
                "candidateCount", candidatePayloads.size(),
                "trace", trace
        ));
        logShortStage(video, "GLOBAL_CRITIC", logSummary(
                "candidateCount", candidatePayloads.size(),
                "metadata", globalCritic.metadata()
        ));
        if (pauseIfRequested(video, "GLOBAL_CRITIC", transcript, graph, trace, Map.of(
                "globalCritic", globalCritic.metadata(),
                "candidatePayloads", candidatePayloads
        ))) {
            return pausedResponse(video);
        }

        List<CreatorShortCandidate> existing = candidateRepository.findByVideoIdOrderByRankIndexAsc(video.getId());
        if (!existing.isEmpty()) {
            candidateRepository.deleteAll(existing);
        }
        List<CreatorShortCandidate> candidates = saveCandidates(video, candidatePayloads, aiOutput);

        generationJobService.updateGenerationJobProgress(jobId, 93, "Rendering short candidates with local FFmpeg", Map.of(
                "activeStage", "RENDERING",
                "candidateCount", candidates.size(),
                "trace", trace
        ));
        logShortStage(video, "RENDERING", logSummary(
                "status", "STARTED",
                "candidateCount", candidates.size()
        ));
        ShortRenderingService.RenderedShortsResult rendering = shortRenderingService.render(video, sourceAsset, candidates, sourceVideoPath);
        trace.addAll(rendering.trace());
        generationJobService.updateGenerationJobProgress(jobId, 94, "Rendered shorts uploaded", Map.of(
                "activeStage", "RENDERING",
                "rendering", rendering.metadata(),
                "trace", trace
        ));
        logShortStage(video, "RENDERING", logSummary(
                "metadata", rendering.metadata()
        ));
        if (pauseIfRequested(video, "RENDERING", transcript, graph, trace, Map.of(
                "rendering", rendering.metadata()
        ))) {
            return pausedResponse(video);
        }
        candidates = candidateRepository.findByVideoIdOrderByRankIndexAsc(video.getId());
        ShortPostRenderQaService.PostRenderQaResult postRenderQa = postRenderQaService.audit(video, candidates);
        trace.addAll(postRenderQa.trace());
        generationJobService.updateGenerationJobProgress(jobId, 96, "Post-render QA complete", Map.of(
                "activeStage", "POST_RENDER_QA",
                "postRenderQa", postRenderQa.metadata(),
                "trace", trace
        ));
        logShortStage(video, "POST_RENDER_QA", logSummary(
                "metadata", postRenderQa.metadata()
        ));
        if (pauseIfRequested(video, "POST_RENDER_QA", transcript, graph, trace, Map.of(
                "postRenderQa", postRenderQa.metadata()
        ))) {
            return pausedResponse(video);
        }
        candidates = candidateRepository.findByVideoIdOrderByRankIndexAsc(video.getId());
        graph = withShortCandidateGraphNodes(graph, candidates);
        if (trace.size() < PIPELINE_STAGES.size()) {
            trace = defaultTrace(trace, videoDna);
        }

        Map<String, Object> transcriptPayload = new LinkedHashMap<>();
        transcriptPayload.put("nodes", transcript);
        transcriptPayload.put("analysisSource", stringValue(videoDna.getOrDefault("analysisSource", "ai_planner")));

        Map<String, Object> tracePayload = new LinkedHashMap<>();
        tracePayload.put("stages", trace);
        tracePayload.put("pipeline", PIPELINE_STAGES);

        Map<String, Object> metadata = mutableMap(video.getMetadata());
        metadata.put("aiProvider", aiOutput.get("provider"));
        metadata.put("aiModel", aiOutput.get("model"));
        metadata.put("aiTokenMetadata", aiResponse.tokenMetadata());
        metadata.put("aiCostMetadata", aiResponse.costMetadata());
        metadata.put("pipelineStrategy", pipelineStrategy.metadata());
        metadata.put("sceneTimeline", continuitySceneTimeline);
        metadata.put("googleVideoUnderstanding", Map.of(
                "mediaBacked", understanding.mediaBacked(),
                "provider", understanding.provider(),
                "model", understanding.model(),
                "transcriptCritic", understanding.transcriptCritic(),
                "videoTypeCritic", understanding.videoTypeCritic(),
                "tokenMetadata", understanding.tokenMetadata(),
                "costMetadata", understanding.costMetadata()
        ));
        metadata.put("fullTranscriptWorker", Map.of(
                "mediaBacked", fullTranscript.mediaBacked(),
                "provider", fullTranscript.provider(),
                "model", fullTranscript.model(),
                "tokenMetadata", fullTranscript.tokenMetadata(),
                "costMetadata", fullTranscript.costMetadata(),
                "metadata", fullTranscript.metadata()
        ));
        metadata.put("deepTranscriptCritic", Map.of(
                "mediaBacked", transcriptCritic.mediaBacked(),
                "provider", transcriptCritic.provider(),
                "model", transcriptCritic.model(),
                "tokenMetadata", transcriptCritic.tokenMetadata(),
                "costMetadata", transcriptCritic.costMetadata(),
                "metadata", transcriptCritic.metadata(),
                "chunkAudits", transcriptCritic.chunkAudits()
        ));
        metadata.put("deepVideoTypeCritic", Map.of(
                "mediaBacked", videoTypeCritic.mediaBacked(),
                "provider", videoTypeCritic.provider(),
                "model", videoTypeCritic.model(),
                "videoDna", videoTypeCritic.videoDna(),
                "videoTypeCritic", videoTypeCritic.videoTypeCritic(),
                "sampleMetadata", videoTypeCritic.sampleMetadata(),
                "tokenMetadata", videoTypeCritic.tokenMetadata(),
                "costMetadata", videoTypeCritic.costMetadata(),
                "metadata", videoTypeCritic.metadata()
        ));
        metadata.put("sceneAnalysis", Map.of(
                "mediaBacked", sceneAnalysis.mediaBacked(),
                "metadata", sceneAnalysis.metadata(),
                "sceneCount", sceneAnalysis.scenes().size(),
                "frameCount", sceneAnalysis.frames().size(),
                "scenes", sceneAnalysis.scenes(),
                "frames", sceneAnalysis.frames()
        ));
        metadata.put("deepSceneCritic", Map.of(
                "mediaBacked", sceneCritic.mediaBacked(),
                "provider", sceneCritic.provider(),
                "model", sceneCritic.model(),
                "sceneCritic", sceneCritic.sceneCritic(),
                "evidenceMetadata", sceneCritic.evidenceMetadata(),
                "tokenMetadata", sceneCritic.tokenMetadata(),
                "costMetadata", sceneCritic.costMetadata(),
                "metadata", sceneCritic.metadata()
        ));
        metadata.put("deepStoryCritic", Map.of(
                "mediaBacked", storyCritic.mediaBacked(),
                "provider", storyCritic.provider(),
                "model", storyCritic.model(),
                "storyUnderstanding", storyCritic.storyUnderstanding(),
                "storyCritic", storyCritic.storyCritic(),
                "tokenMetadata", storyCritic.tokenMetadata(),
                "costMetadata", storyCritic.costMetadata(),
                "metadata", storyCritic.metadata()
        ));
        metadata.put("robustInterestingness", Map.of(
                "evidenceBacked", interestingnessScoring.evidenceBacked(),
                "topMoments", interestingnessScoring.topMoments(),
                "candidateWindows", interestingnessScoring.candidateWindows(),
                "interestingness", interestingnessScoring.interestingness(),
                "interestingnessCritic", interestingnessCritic.interestingnessCritic(),
                "criticMetadata", interestingnessCritic.metadata(),
                "metadata", interestingnessScoring.metadata()
        ));
        metadata.put("realGraphBuilder", graphBuild.metadata());
        metadata.put("storyBeatPlanning", Map.of(
                "metadata", storyBeatPlanning.metadata(),
                "intentCount", storyBeatPlanning.intents().size(),
                "planCount", storyBeatPlanning.plans().size(),
                "intents", storyBeatPlanning.intents()
        ));
        metadata.put("visualStoryComposition", visualStoryComposition.metadata());
        metadata.put("realCompressionPlanner", Map.of(
                "metadata", compressionPlanning.metadata(),
                "planCount", compressionPlans.size(),
                "storyBeatPlanCount", storyBeatPlanning.plans().size(),
                "visualCompositionPlanCount", visualStoryComposition.plans().size(),
                "compressionWorkerPlanCount", compressionPlanning.plans().size(),
                "plans", compressionPlans
        ));
        metadata.put("hookGeneration", hookGeneration.metadata());
        metadata.put("criticRepairWorkers", criticRepair.metadata());
        metadata.put("criticRepairAttempts", criticRepairAttempts);
        metadata.put("visualEnhancement", visualEnhancement.metadata());
        metadata.put("visualCritic", visualCritic.metadata());
        metadata.put("continuityCritic", continuityCritic.metadata());
        metadata.put("sceneTimeline", continuitySceneTimeline);
        metadata.put("candidateRanking", candidateRanking.metadata());
        metadata.put("globalCritic", globalCritic.metadata());
        metadata.put("rendering", rendering.metadata());
        metadata.put("postRenderQa", postRenderQa.metadata());
        metadata.put("renderingStatus", defaultString(stringValue(rendering.metadata().get("status")), "COMPLETED"));
        Map<String, Object> pipelineOutcome = aggregateShortPipelineStatus(candidates);
        metadata.put("pipelineOutcome", pipelineOutcome);
        metadata.put("humanLoopReview", humanLoopReviewMetadata(transcript, sceneAnalysis, sceneCritic, storyCritic, interestingnessScoring, transcriptCritic, pipelineOutcome));
        metadata.put("agentContractVersion", 1);

        video.setStatus(stringValue(pipelineOutcome.get("videoStatus")));
        video.setVideoDna(videoDna);
        video.setTranscriptPayload(transcriptPayload);
        video.setGraphPayload(graph);
        video.setTracePayload(tracePayload);
        video.setMetadata(metadata);
        video.setCompletedAt(OffsetDateTime.now());
        CreatorShortVideo saved = saveShortVideoWithTransientRetry(video, "final_pipeline_persistence", jobId);

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("shortVideoId", saved.getId().toString());
        output.put("status", saved.getStatus());
        output.put("sourceAssetId", sourceAsset.getId().toString());
        output.put("videoDna", videoDna);
        output.put("transcript", transcript);
        output.put("graph", graph);
        output.put("pipelineStrategy", pipelineStrategy.metadata());
        output.put("sceneTimeline", continuitySceneTimeline);
        output.put("trace", trace);
        output.put("candidates", candidates.stream().map(candidate -> Map.of(
                "candidateId", candidate.getId().toString(),
                "rankIndex", candidate.getRankIndex(),
                "title", candidate.getTitle(),
                "status", candidate.getStatus()
        )).toList());
        output.put("pipelineOutcome", pipelineOutcome);
        if (Boolean.TRUE.equals(pipelineOutcome.get("jobShouldFail"))) {
            generationJobService.failGenerationJob(jobId, stringValue(pipelineOutcome.get("failureReason")), output);
            log.warn(
                    "Generate Shorts pipeline finished with failed outcome jobId={} videoId={} status={} outcome={}",
                    jobId,
                    saved.getId(),
                    saved.getStatus(),
                    pipelineOutcome
            );
        } else {
            generationJobService.completeGenerationJob(jobId, output);
            log.info(
                    "Generate Shorts pipeline completed jobId={} videoId={} status={} outcome={}",
                    jobId,
                    saved.getId(),
                    saved.getStatus(),
                    pipelineOutcome
            );
        }
        return toResponse(saved);
    }

    private Map<String, Object> aggregateShortPipelineStatus(List<CreatorShortCandidate> candidates) {
        List<CreatorShortCandidate> safeCandidates = candidates == null ? List.of() : candidates;
        int rendered = 0;
        int warnings = 0;
        int failed = 0;
        int blocked = 0;
        int queued = 0;
        int deferred = 0;
        for (CreatorShortCandidate candidate : safeCandidates) {
            String status = defaultString(candidate == null ? null : candidate.getStatus(), "").toUpperCase(Locale.ROOT);
            Map<String, Object> manifest = mutableMap(candidate == null ? null : candidate.getRenderManifest());
            String renderStatus = defaultString(manifest.get("renderStatus"), "").toUpperCase(Locale.ROOT);
            String qaStatus = defaultString(manifest.get("postRenderQaStatus"), "").toUpperCase(Locale.ROOT);
            boolean terminalFailure = status.contains("FAILED")
                    || renderStatus.contains("FAILED")
                    || status.contains("BLOCKED")
                    || renderStatus.contains("BLOCKED")
                    || "FAIL".equals(qaStatus);
            if (status.contains("QUEUED") || renderStatus.contains("QUEUED")) {
                queued++;
            }
            if (renderStatus.contains("DEFERRED") || (candidate != null && candidate.getAssetId() == null && !terminalFailure)) {
                deferred++;
            }
            if ("RENDERED".equals(status) || "RENDERED".equals(renderStatus)) {
                rendered++;
            }
            if (status.contains("WARNING") || renderStatus.contains("WARNING") || "WARN".equals(qaStatus)) {
                warnings++;
                if (rendered == 0 && status.startsWith("RENDERED")) {
                    rendered++;
                }
            }
            if (status.contains("FAILED") || renderStatus.contains("FAILED") || "FAIL".equals(qaStatus)) {
                failed++;
            }
            if (status.contains("BLOCKED") || renderStatus.contains("BLOCKED")) {
                blocked++;
            }
        }
        int total = safeCandidates.size();
        int usable = 0;
        for (CreatorShortCandidate candidate : safeCandidates) {
            if (isUsableRenderedCandidate(candidate)) {
                usable++;
            }
        }
        boolean allTerminalFailed = total > 0 && usable == 0 && queued == 0 && deferred == 0 && (failed + blocked) > 0;
        String videoStatus = allTerminalFailed
                ? "RENDER_FAILED"
                : (failed > 0 || blocked > 0 || warnings > 0 || queued > 0 || deferred > 0 ? "READY_FOR_REVIEW_WITH_WARNINGS" : "READY_FOR_REVIEW");
        Map<String, Object> aggregate = new LinkedHashMap<>();
        aggregate.put("videoStatus", videoStatus);
        aggregate.put("jobShouldFail", allTerminalFailed);
        aggregate.put("failureReason", allTerminalFailed ? "No generated short passed render/QA." : "");
        aggregate.put("candidateCount", total);
        aggregate.put("usableRenderedCount", usable);
        aggregate.put("renderedCount", rendered);
        aggregate.put("warningCount", warnings);
        aggregate.put("failedCount", failed);
        aggregate.put("blockedCount", blocked);
        aggregate.put("queuedCount", queued);
        aggregate.put("deferredCount", deferred);
        aggregate.put("updatedAt", OffsetDateTime.now().toString());
        return aggregate;
    }

    private boolean isUsableRenderedCandidate(CreatorShortCandidate candidate) {
        if (candidate == null) {
            return false;
        }
        String status = defaultString(candidate.getStatus(), "").toUpperCase(Locale.ROOT);
        Map<String, Object> manifest = mutableMap(candidate.getRenderManifest());
        String renderStatus = defaultString(manifest.get("renderStatus"), "").toUpperCase(Locale.ROOT);
        String qaStatus = defaultString(manifest.get("postRenderQaStatus"), "").toUpperCase(Locale.ROOT);
        if ("FAIL".equals(qaStatus) || status.contains("FAILED") || renderStatus.contains("FAILED") || status.contains("BLOCKED") || renderStatus.contains("BLOCKED")) {
            return false;
        }
        return status.startsWith("RENDERED") || renderStatus.startsWith("RENDERED");
    }

    private Map<String, Object> humanSemanticReview(CreatorShortVideo video, Map<String, Object> metadata, Map<String, Object> editDecisionList, Map<String, Object> patch) {
        String sourceText = sourceTextForSemanticReview(metadata, editDecisionList);
        Map<String, String> sourceTextByNode = sourceTextByNode(editDecisionList);
        List<Map<String, Object>> issues = new ArrayList<>();
        List<Map<String, Object>> fieldReviews = new ArrayList<>();

        fieldReviews.add(reviewSemanticField("intent", stringValue(patch.get("intent")), sourceText, sourceText, 0.10));
        fieldReviews.add(reviewSemanticField("hook", stringValue(patch.get("hook")), sourceText, sourceText, 0.12));
        for (Map<String, Object> beat : listOfMaps(patch.get("beats"))) {
            String nodeId = stringValue(beat.get("nodeId")).trim();
            String nodeSource = defaultString(sourceTextByNode.get(nodeId), sourceText);
            Map<String, Object> review = reviewSemanticField("beat:" + defaultString(nodeId, "unmapped"), stringValue(beat.get("text")), nodeSource, sourceText, 0.14);
            review.put("nodeId", nodeId);
            fieldReviews.add(review);
        }

        int blockingCount = 0;
        double supportTotal = 0.0;
        int supportedFields = 0;
        for (Map<String, Object> fieldReview : fieldReviews) {
            if (stringValue(fieldReview.get("text")).isBlank()) {
                continue;
            }
            supportedFields++;
            supportTotal += doubleValue(fieldReview.get("supportScore"), 0.0);
            for (Map<String, Object> issue : listOfMaps(fieldReview.get("issues"))) {
                issues.add(issue);
                if ("high".equalsIgnoreCase(stringValue(issue.get("severity")))) {
                    blockingCount++;
                }
            }
        }

        if (supportedFields > 0 && sourceText.isBlank()) {
            issues.add(Map.of("severity", "medium", "code", "NO_SOURCE_TEXT_FOR_CONTEXT_CHECK", "summary", "Human edit needs review because source transcript text is unavailable."));
        }
        double supportScore = supportedFields == 0 ? 1.0 : supportTotal / supportedFields;
        if (supportedFields > 0 && supportScore < 0.12 && blockingCount == 0) {
            issues.add(Map.of("severity", "medium", "code", "LOW_OVERALL_SOURCE_SUPPORT", "summary", "Human edit has weak support across the original short source text."));
        }

        Map<String, Object> review = new LinkedHashMap<>();
        review.put("source", "human_loop_semantic_context_guard_v2");
        review.put("status", blockingCount > 0 ? "FAIL" : issues.isEmpty() ? "PASS" : "NEEDS_HUMAN_REVIEW");
        review.put("requiresHumanReview", !issues.isEmpty());
        review.put("blockingIssueCount", blockingCount);
        review.put("supportScore", roundSeconds(supportScore));
        review.put("mustStayInsideOriginalStory", true);
        review.put("issues", issues);
        review.put("fieldReviews", fieldReviews);
        review.put("sourceNodeIds", sourceNodeIds(editDecisionList));
        review.put("sceneIds", sceneIds(editDecisionList));
        review.put("updatedAt", OffsetDateTime.now().toString());
        return review;
    }

    private Map<String, Object> reviewSemanticField(String field, String text, String localSourceText, String globalSourceText, double minimumOverlap) {
        String cleanText = defaultString(text, "").trim();
        String localSource = defaultString(localSourceText, "");
        String globalSource = defaultString(globalSourceText, "");
        double localOverlap = semanticTokenOverlap(cleanText, localSource);
        double globalOverlap = semanticTokenOverlap(cleanText, globalSource);
        double supportScore = Math.max(localOverlap, globalOverlap);
        List<Map<String, Object>> issues = new ArrayList<>();
        List<String> unsupportedTokens = unsupportedSemanticTokens(cleanText, globalSource);
        List<String> unsupportedNumbers = unsupportedNumbers(cleanText, globalSource);

        if (!cleanText.isBlank() && globalSource.isBlank()) {
            issues.add(Map.of("severity", "medium", "code", "NO_SOURCE_TEXT", "field", field, "summary", "No source transcript text is available to verify this edit."));
        } else if (!cleanText.isBlank() && supportScore < minimumOverlap && unsupportedTokens.size() >= 3) {
            issues.add(Map.of("severity", supportScore < 0.05 ? "high" : "medium", "code", "UNSUPPORTED_STORY_CLAIM", "field", field, "summary", "This edit appears to introduce claims that are not present in the selected source nodes."));
        }
        if (!unsupportedNumbers.isEmpty()) {
            issues.add(Map.of("severity", "high", "code", "UNSUPPORTED_NUMERIC_CLAIM", "field", field, "summary", "Numeric claims must come from the original story.", "numbers", unsupportedNumbers));
        }
        if (hasUnsupportedAbsoluteClaim(cleanText, globalSource)) {
            issues.add(Map.of("severity", "medium", "code", "UNSUPPORTED_ABSOLUTE_CLAIM", "field", field, "summary", "Avoid absolute claims unless the source explicitly says them."));
        }

        Map<String, Object> review = new LinkedHashMap<>();
        review.put("field", field);
        review.put("text", cleanText);
        review.put("supportScore", roundSeconds(supportScore));
        review.put("localOverlap", roundSeconds(localOverlap));
        review.put("globalOverlap", roundSeconds(globalOverlap));
        review.put("unsupportedTokenSample", unsupportedTokens.stream().limit(8).toList());
        review.put("issues", issues);
        return review;
    }

    private boolean semanticReviewHasBlockingIssue(Map<String, Object> review) {
        if ("FAIL".equalsIgnoreCase(stringValue(review.get("status")))) {
            return true;
        }
        for (Map<String, Object> issue : listOfMaps(review.get("issues"))) {
            if ("high".equalsIgnoreCase(stringValue(issue.get("severity")))) {
                return true;
            }
        }
        return false;
    }

    private Map<String, String> sourceTextByNode(Map<String, Object> editDecisionList) {
        Map<String, String> result = new LinkedHashMap<>();
        for (Map<String, Object> segment : listOfMaps(editDecisionList.get("segments"))) {
            String nodeId = stringValue(segment.get("nodeId")).trim();
            if (nodeId.isBlank()) {
                continue;
            }
            String text = String.join(" ",
                    stringValue(segment.get("transcript")),
                    stringValue(segment.get("text")),
                    stringValue(segment.get("summary")),
                    stringValue(segment.get("reason"))
            ).trim();
            if (!text.isBlank()) {
                result.merge(nodeId, text.toLowerCase(Locale.ROOT), (left, right) -> left + " " + right);
            }
        }
        return result;
    }
    private String sourceTextForSemanticReview(Map<String, Object> metadata, Map<String, Object> editDecisionList) {
        StringBuilder builder = new StringBuilder();
        for (Map<String, Object> segment : listOfMaps(editDecisionList.get("segments"))) {
            builder.append(' ').append(stringValue(segment.get("transcript")));
            builder.append(' ').append(stringValue(segment.get("text")));
            builder.append(' ').append(stringValue(segment.get("summary")));
        }
        builder.append(' ').append(mapValue(metadata.get("storyIntent")).values());
        builder.append(' ').append(mapValue(metadata.get("hookPlan")).values());
        return builder.toString().toLowerCase(Locale.ROOT);
    }

    private double semanticTokenOverlap(String userText, String sourceText) {
        java.util.Set<String> userTokens = semanticTokens(userText);
        java.util.Set<String> sourceTokens = semanticTokens(sourceText);
        if (userTokens.isEmpty()) {
            return 1.0;
        }
        int overlap = 0;
        for (String token : userTokens) {
            if (sourceTokens.contains(token)) {
                overlap++;
            }
        }
        return overlap / (double) userTokens.size();
    }

    private java.util.Set<String> semanticTokens(String text) {
        java.util.Set<String> tokens = new java.util.HashSet<>();
        for (String token : defaultString(text, "").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", " ").split("\\s+")) {
            if (token.length() >= 4 && !semanticStopWords().contains(token)) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private List<String> unsupportedSemanticTokens(String text, String sourceText) {
        java.util.Set<String> sourceTokens = semanticTokens(sourceText);
        List<String> unsupported = new ArrayList<>();
        for (String token : semanticTokens(text)) {
            if (!sourceTokens.contains(token) && !claimSoftWords().contains(token)) {
                unsupported.add(token);
            }
        }
        return unsupported;
    }

    private List<String> unsupportedNumbers(String text, String sourceText) {
        java.util.Set<String> sourceNumbers = extractNumbers(sourceText);
        List<String> unsupported = new ArrayList<>();
        for (String number : extractNumbers(text)) {
            if (!sourceNumbers.contains(number)) {
                unsupported.add(number);
            }
        }
        return unsupported;
    }

    private java.util.Set<String> extractNumbers(String text) {
        java.util.Set<String> result = new java.util.HashSet<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\b\\d+(?:\\.\\d+)?%?\\b").matcher(defaultString(text, ""));
        while (matcher.find()) {
            result.add(matcher.group().toLowerCase(Locale.ROOT));
        }
        return result;
    }

    private boolean hasUnsupportedAbsoluteClaim(String text, String sourceText) {
        java.util.Set<String> absolutes = java.util.Set.of("guaranteed", "always", "never", "only", "best", "worst", "everyone", "nobody", "proven");
        java.util.Set<String> sourceTokens = semanticTokens(sourceText);
        for (String token : semanticTokens(text)) {
            if (absolutes.contains(token) && !sourceTokens.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private java.util.Set<String> semanticStopWords() {
        return java.util.Set.of("this", "that", "with", "from", "have", "will", "your", "about", "into", "there", "their", "they", "them", "then", "than", "what", "when", "where", "which", "while", "because", "before", "after", "also", "more", "most", "very", "just", "like");
    }

    private java.util.Set<String> claimSoftWords() {
        return java.util.Set.of("story", "short", "hook", "viewer", "watch", "moment", "reason", "beat", "scene", "clip", "shows", "learn", "make", "makes", "important");
    }

    private Map<String, Object> humanLoopReviewMetadata(
            List<Map<String, Object>> transcript,
            ShortSceneAnalysisService.SceneAnalysisResult sceneAnalysis,
            GoogleShortSceneCriticService.SceneCriticResult sceneCritic,
            GoogleShortStoryCriticService.StoryCriticResult storyCritic,
            ShortInterestingnessScoringService.InterestingnessScoringResult interestingnessScoring,
            GoogleShortTranscriptCriticService.TranscriptCriticResult transcriptCritic,
            Map<String, Object> pipelineOutcome
    ) {
        List<Map<String, Object>> flags = new ArrayList<>();
        if (interestingnessScoring == null || interestingnessScoring.candidateWindows().isEmpty()) {
            flags.add(Map.of("code", "NO_STRONG_MOMENT_DETECTED", "summary", "Human should choose or lock the moment because the scoring worker found no strong candidate windows."));
        }
        if (transcript == null || transcript.isEmpty()) {
            flags.add(Map.of("code", "TRANSCRIPT_UNRELIABLE", "summary", "Human should verify captions and trims because transcript nodes are missing."));
        }
        Map<String, Object> transcriptMetadata = transcriptCritic == null ? Map.of() : transcriptCritic.metadata();
        String transcriptStatus = defaultString(transcriptMetadata.get("status"), "");
        if ("FAIL".equalsIgnoreCase(transcriptStatus)) {
            flags.add(Map.of("code", "TRANSCRIPT_TIMING_UNSAFE", "summary", "Human should verify captions and source trims because the transcript critic found unsafe timing or coverage issues."));
        }
        boolean transcriptFirstScenes = isTranscriptFirstSceneStrategy(sceneAnalysis, sceneCritic);
        if (transcriptFirstScenes) {
            flags.add(Map.of("code", "TRANSCRIPT_FIRST_VISUAL_PREVIEW", "summary", "Quick transcript timeline used transcript-backed cuts; human preview should verify final visual framing before approval."));
        } else if (sceneAnalysis == null || !sceneAnalysis.mediaBacked() || sceneAnalysis.scenes().isEmpty()) {
            flags.add(Map.of("code", "SCENE_DETECTION_WEAK", "summary", "Human should inspect visuals because scene detection did not produce reliable media-backed scenes."));
        }
        if (!transcriptFirstScenes && (sceneCritic == null || !sceneCritic.mediaBacked())) {
            flags.add(Map.of("code", "VISUAL_CONTEXT_WEAK", "summary", "Human should review visual-only moments because deep scene critic was not media-backed."));
        }
        Map<String, Object> sceneCriticPayload = sceneCritic == null ? Map.of() : sceneCritic.sceneCritic();
        String sceneCriticStatus = defaultString(sceneCriticPayload.get("status"), "");
        if ("FAIL".equalsIgnoreCase(sceneCriticStatus)
                || booleanValue(sceneCriticPayload.get("fallbackApplied"), false)
                || doubleValue(sceneCriticPayload.get("confidence"), 1.0) < 0.5) {
            flags.add(Map.of("code", "VISUAL_EVIDENCE_UNSAFE", "summary", "Human should preview visuals because scene evidence was weak, repaired, or unsafe for blind automated cuts."));
        }
        Map<String, Object> storyMetadata = storyCritic == null ? Map.of() : mapValue(storyCritic.metadata());
        Map<String, Object> storyCriticPayload = mapValue(storyMetadata.get("storyCritic"));
        if (booleanValue(storyCriticPayload.get("fallbackApplied"), false)
                || doubleValue(storyCriticPayload.get("confidence"), 1.0) < 0.5
                || "FAIL".equalsIgnoreCase(defaultString(storyCriticPayload.get("status"), ""))) {
            flags.add(Map.of("code", "STORY_CONTEXT_WEAK", "summary", "Human should verify story context because the story critic had low confidence or required deterministic preservation repair."));
        }
        if (intValue(pipelineOutcome.get("failedCount"), 0) > 0 || intValue(pipelineOutcome.get("warningCount"), 0) > 0) {
            flags.add(Map.of("code", "RENDER_OR_QA_WARNINGS", "summary", "Human should review rendered candidates with warning or failed QA status."));
        }
        flags.add(Map.of("code", "FRAME_PERFECT_PREVIEW_REQUIRED", "summary", "Human preview is required for frame-perfect trimming and final creative approval."));
        Map<String, Object> review = new LinkedHashMap<>();
        review.put("source", "human_loop_readiness_guard");
        review.put("required", !flags.isEmpty());
        review.put("flags", flags);
        review.put("updatedAt", OffsetDateTime.now().toString());
        return review;
    }
    private Map<String, Object> shortsQueuePayload(CreatorShortVideo video, CreatorAsset sourceAsset, GenerateShortsRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("jobType", JOB_TYPE);
        payload.put("shortVideoId", video.getId().toString());
        payload.put("sourceAssetId", sourceAsset.getId().toString());
        payload.put("tenantId", video.getTenantId());
        payload.put("userId", video.getUserId());
        payload.put("projectId", video.getProjectId() == null ? null : video.getProjectId().toString());
        payload.put("request", requestMap(request));
        payload.put("queuedAt", OffsetDateTime.now().toString());
        payload.put("shortsQueue", Map.of(
                "jobType", JOB_TYPE,
                "sourceAssetId", sourceAsset.getId().toString(),
                "shortVideoId", video.getId().toString()
        ));
        return payload;
    }

    private void publishExistingGenerationJobAfterCommit(String kafkaTopic, UUID jobId, Map<String, Object> payload) {
        Map<String, Object> safePayload = new LinkedHashMap<>(payload == null ? Map.of() : payload);
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            generationJobService.publishExistingGenerationJob(kafkaTopic, jobId, safePayload);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                generationJobService.publishExistingGenerationJob(kafkaTopic, jobId, safePayload);
            }
        });
    }

    private Map<String, Object> requestMap(GenerateShortsRequest request) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (request == null) {
            return map;
        }
        map.put("projectId", request.projectId() == null ? null : request.projectId().toString());
        map.put("title", request.title());
        map.put("platform", request.platform());
        map.put("targetDurationSeconds", request.targetDurationSeconds());
        map.put("requestedShorts", request.requestedShorts());
        map.put("reviewMode", request.reviewMode());
        map.put("creatorProfileJson", request.creatorProfileJson());
        map.put("notes", request.notes());
        map.put("executionMode", request.executionMode());
        return map;
    }

    private GenerateShortsRequest requestFromVideo(CreatorShortVideo video) {
        Map<String, Object> settings = mutableMap(video == null ? null : video.getSettings());
        Object creatorProfile = settings.get("creatorProfile");
        String creatorProfileJson = creatorProfile instanceof Map<?, ?> || creatorProfile instanceof List<?>
                ? toJson(creatorProfile)
                : defaultString(creatorProfile, null);
        return new GenerateShortsRequest(
                video == null ? null : video.getProjectId(),
                video == null ? null : video.getTitle(),
                video == null ? null : video.getPlatform(),
                video == null ? null : video.getTargetDurationSeconds(),
                video == null ? null : video.getRequestedShorts(),
                video == null ? null : video.getReviewMode(),
                creatorProfileJson,
                defaultString(settings.get("notes"), ""),
                defaultString(settings.get("executionMode"), "AUTO")
        );
    }

    private boolean isRestartableShortJobStatus(String status) {
        return "COMPLETED".equalsIgnoreCase(status)
                || "FAILED".equalsIgnoreCase(status)
                || "CANCELLED".equalsIgnoreCase(status);
    }

    private boolean isTerminalShortJobStatus(String status) {
        return "COMPLETED".equalsIgnoreCase(status)
                || "FAILED".equalsIgnoreCase(status)
                || "CANCELLED".equalsIgnoreCase(status)
                || "ERROR".equalsIgnoreCase(status);
    }

    private UUID uuidValue(Object value) {
        if (value instanceof UUID uuid) {
            return uuid;
        }
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(String.valueOf(value));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private String normalizeRestartMode(String mode) {
        String normalized = defaultString(mode, "RESTART_FROM_INGESTED").trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        return switch (normalized) {
            case "REPLAY", "REPLAY_STAGE", "STAGE_REPLAY" -> "REPLAY_STAGE";
            default -> "RESTART_FROM_INGESTED";
        };
    }

    private String normalizeReplayStage(Object stage) {
        String normalized = defaultString(stage, "").trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        if (normalized.isBlank()) {
            return "";
        }
        return PIPELINE_STAGES.contains(normalized) && !"INGESTION".equals(normalized) && !"COMPLETED".equals(normalized)
                ? normalized
                : "";
    }

    private boolean isTransientDatabaseFailure(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor != null) {
            if (cursor instanceof DataAccessResourceFailureException
                    || cursor instanceof CannotCreateTransactionException
                    || cursor instanceof JDBCConnectionException) {
                return true;
            }
            if (cursor instanceof SQLException sqlException) {
                String sqlState = sqlException.getSQLState();
                if (sqlState != null && sqlState.startsWith("08")) {
                    return true;
                }
            }
            cursor = cursor.getCause();
        }
        return false;
    }

    private CreatorShortVideo saveShortVideoWithTransientRetry(CreatorShortVideo video, String operation, UUID jobId) {
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                return videoRepository.saveAndFlush(video);
            } catch (RuntimeException ex) {
                if (!isTransientDatabaseFailure(ex) || attempt == 3) {
                    throw ex;
                }
                lastFailure = ex;
                log.warn(
                        "Generate Shorts transient database failure while saving video operation={} attempt={} nextAttempt={} jobId={} videoId={} errorType={} errorMessage={}",
                        operation,
                        attempt,
                        attempt + 1,
                        jobId,
                        video == null ? null : video.getId(),
                        ex.getClass().getSimpleName(),
                        ex.getMessage()
                );
                sleepBeforeDatabaseRetry(attempt);
            }
        }
        throw lastFailure == null ? new IllegalStateException("Short video save retry failed without captured exception.") : lastFailure;
    }

    private void sleepBeforeDatabaseRetry(int attempt) {
        try {
            Thread.sleep(Math.min(2000L, 250L * (1L << Math.max(0, attempt - 1))));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while retrying transient database write.", interrupted);
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
    private void failVideo(UUID videoId, UUID jobId, String message) {
        videoRepository.findById(videoId).ifPresent(video -> {
            video.setStatus("FAILED");
            Map<String, Object> metadata = mutableMap(video.getMetadata());
            metadata.put("failureReason", defaultString(message, "Shorts generation failed."));
            metadata.put("failedAt", OffsetDateTime.now().toString());
            video.setMetadata(metadata);
            video.setCompletedAt(OffsetDateTime.now());
            videoRepository.save(video);
        });
        if (jobId != null) {
            generationJobService.failGenerationJob(jobId, defaultString(message, "Shorts generation failed."));
        }
    }

    private void markVisualAnalysisFailed(UUID videoId, UUID jobId, String message) {
        if (videoId == null) {
            return;
        }
        videoRepository.findById(videoId).ifPresent(video -> {
            Map<String, Object> metadata = mutableMap(video.getMetadata());
            Map<String, Object> visualAnalysis = mapValue(metadata.get("visualAnalysis"));
            visualAnalysis.put("status", "FAILED");
            visualAnalysis.put("source", "optional_visual_analysis");
            visualAnalysis.put("jobId", jobId == null ? "" : jobId.toString());
            visualAnalysis.put("failedAt", OffsetDateTime.now().toString());
            visualAnalysis.put("message", defaultString(message, "Visual analysis failed."));
            metadata.put("visualAnalysis", visualAnalysis);
            metadata.put("visualAnalysisJobId", jobId == null ? "" : jobId.toString());
            video.setMetadata(metadata);
            videoRepository.save(video);
        });
    }

    private Map<String, Object> buildAiInput(
            CreatorShortVideo video,
            CreatorAsset sourceAsset,
            GenerateShortsRequest request,
            GoogleShortVideoUnderstandingService.ShortVideoUnderstandingResult understanding,
            GoogleShortFullTranscriptWorkerService.FullTranscriptResult fullTranscript,
            GoogleShortTranscriptCriticService.TranscriptCriticResult transcriptCritic,
            GoogleShortVideoTypeCriticService.VideoTypeCriticResult videoTypeCritic,
            ShortSceneAnalysisService.SceneAnalysisResult sceneAnalysis,
            GoogleShortSceneCriticService.SceneCriticResult sceneCritic,
            GoogleShortStoryCriticService.StoryCriticResult storyCritic,
            ShortInterestingnessScoringService.InterestingnessScoringResult interestingnessScoring,
            ShortInterestingnessCriticService.InterestingnessCriticResult interestingnessCritic,
            ShortVideoGraphBuilderService.GraphBuildResult graphBuild
    ) {
        List<Map<String, Object>> understandingTranscriptForPrompt = plannerPromptSample(understanding.transcript(), MAX_PLANNER_TRANSCRIPT_NODES);
        List<Map<String, Object>> fullTranscriptForPrompt = plannerPromptSample(fullTranscript.transcript(), MAX_PLANNER_TRANSCRIPT_NODES);
        List<Map<String, Object>> sceneTranscriptForPrompt = plannerPromptSample(sceneAnalysis.transcript(), MAX_PLANNER_TRANSCRIPT_NODES);
        List<Map<String, Object>> scenesForPrompt = plannerPromptSample(sceneAnalysis.scenes(), MAX_PLANNER_SCENES);
        Map<String, Object> graphForPrompt = graphForPlanner(graphBuild.graph());
        Map<String, Object> plannerCompaction = new LinkedHashMap<>();
        plannerCompaction.put("enabled", true);
        plannerCompaction.put("maxTranscriptNodes", MAX_PLANNER_TRANSCRIPT_NODES);
        plannerCompaction.put("maxScenes", MAX_PLANNER_SCENES);
        plannerCompaction.put("maxGraphNodes", MAX_PLANNER_GRAPH_NODES);
        plannerCompaction.put("maxGraphEdges", MAX_PLANNER_GRAPH_EDGES);
        plannerCompaction.put("understandingTranscriptNodeCount", understanding.transcript().size());
        plannerCompaction.put("fullTranscriptNodeCount", fullTranscript.transcript().size());
        plannerCompaction.put("sceneTranscriptNodeCount", sceneAnalysis.transcript().size());
        plannerCompaction.put("sceneCount", sceneAnalysis.scenes().size());
        plannerCompaction.put("graphNodeCount", listOfMaps(graphBuild.graph().get("nodes")).size());
        plannerCompaction.put("graphEdgeCount", listOfMaps(graphBuild.graph().get("edges")).size());
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("shortVideoId", video.getId().toString());
        variables.put("title", video.getTitle());
        variables.put("originalFilename", video.getOriginalFileName());
        variables.put("platform", video.getPlatform());
        variables.put("targetDurationSeconds", video.getTargetDurationSeconds());
        variables.put("requestedShorts", video.getRequestedShorts());
        variables.put("reviewMode", video.getReviewMode());
        variables.put("notes", defaultString(request.notes(), ""));
        variables.put("creatorProfile", parseJsonObject(request.creatorProfileJson()));
        variables.put("sourceAsset", sourceAssetMap(sourceAsset));
        variables.put("googleVideoUnderstanding", Map.of(
                "mediaBacked", understanding.mediaBacked(),
                "videoDna", understanding.videoDna(),
                "transcript", understandingTranscriptForPrompt,
                "transcriptCritic", understanding.transcriptCritic(),
                "videoTypeCritic", understanding.videoTypeCritic()
        ));
        variables.put("fullTranscriptWorker", Map.of(
                "mediaBacked", fullTranscript.mediaBacked(),
                "metadata", fullTranscriptMetadataForPrompt(fullTranscript.metadata()),
                "transcript", fullTranscriptForPrompt
        ));
        variables.put("deepTranscriptCritic", Map.of(
                "mediaBacked", transcriptCritic.mediaBacked(),
                "metadata", transcriptCriticMetadataForPrompt(transcriptCritic.metadata()),
                "chunkAudits", transcriptCriticAuditsForPrompt(transcriptCritic.chunkAudits())
        ));
        variables.put("deepVideoTypeCritic", Map.of(
                "mediaBacked", videoTypeCritic.mediaBacked(),
                "videoDna", videoTypeCritic.videoDna(),
                "videoTypeCritic", videoTypeCritic.videoTypeCritic(),
                "sampleMetadata", videoTypeCritic.sampleMetadata(),
                "metadata", videoTypeCriticMetadataForPrompt(videoTypeCritic.metadata())
        ));
        variables.put("sceneAnalysis", Map.of(
                "mediaBacked", sceneAnalysis.mediaBacked(),
                "metadata", sceneAnalysis.metadata(),
                "scenes", scenesForPrompt,
                "frames", frameMetadataForPrompt(sceneAnalysis.frames()),
                "transcript", sceneTranscriptForPrompt
        ));
        variables.put("deepSceneCritic", Map.of(
                "mediaBacked", sceneCritic.mediaBacked(),
                "sceneCritic", sceneCritic.sceneCritic(),
                "evidenceMetadata", sceneCritic.evidenceMetadata(),
                "metadata", sceneCriticMetadataForPrompt(sceneCritic.metadata())
        ));
        variables.put("storyUnderstanding", storyCritic.storyUnderstanding());
        variables.put("deepStoryCritic", Map.of(
                "mediaBacked", storyCritic.mediaBacked(),
                "storyCritic", storyCritic.storyCritic(),
                "metadata", storyCriticMetadataForPrompt(storyCritic.metadata())
        ));
        variables.put("robustInterestingness", interestingnessForPrompt(interestingnessScoring, interestingnessCritic));
        variables.put("realGraph", graphForPrompt);
        variables.put("plannerCompaction", plannerCompaction);
        variables.put("timelinePlannerAgent", timelinePlannerAgentContract());
        variables.put("uiTimelineProjectAgent", uiTimelineProjectAgentContract());
        variables.put("pipeline", PIPELINE_STAGES);

        Map<String, Object> input = new LinkedHashMap<>(variables);
        input.put("renderedPrompt", renderShortsPrompt(variables));
        input.put("requiredOutput", Map.of(
                "videoDna", "VideoDNA object with primaryType, confidence, structureType, analysisSource",
                "transcript", "array of VideoNode objects",
                "graph", "nodes, edges, and graphViews",
                "candidates", "ranked short candidates with editDecisionList, captionPlan, renderManifest, metadata.timelinePlanner, metadata.uiTimelineProject, and timeline-planner editorial metadata fields",
                "trace", "agent trace rows for every completed or skipped stage"
        ));
        return input;
    }

    private Map<String, Object> timelinePlannerAgentContract() {
        Map<String, Object> contract = new LinkedHashMap<>();
        contract.put("role", "AI Timeline Planning Agent");
        contract.put("mission", "Convert the already-selected story into an editable browser video-editor timeline without changing the story or selected clip.");
        contract.put("responsibilities", List.of(
                "visual pacing",
                "layout selection",
                "scene sequencing",
                "supporting footage placement",
                "evidence placement",
                "pattern interrupts",
                "asset expansion requests"
        ));
        contract.put("nonResponsibilities", List.of(
                "story selection",
                "clip selection",
                "transcript generation",
                "video rendering",
                "FFmpeg command generation",
                "Remotion code generation",
                "coordinates",
                "effect implementation"
        ));
        contract.put("styles", List.of(
                "AUTO",
                "JOURNALISM_DOUBLE_SCREEN",
                "DOCUMENTARY",
                "PODCAST_QA",
                "FINANCE_EXPLAINER",
                "VIRAL_SHORTS",
                "EDUCATIONAL",
                "STORYTELLING"
        ));
        contract.put("layoutTypes", List.of(
                "FULL_SCREEN_SPEAKER",
                "DOUBLE_SCREEN",
                "FULL_FOOTAGE",
                "PICTURE_IN_PICTURE",
                "EVIDENCE_VIEW",
                "SCREENSHOT_VIEW",
                "MAP_VIEW",
                "HEADLINE_CARD",
                "STAT_CARD",
                "CONCLUSION_CARD"
        ));
        contract.put("effectTypes", List.of(
                "LIGHTNING_FLASH",
                "WHITE_FLASH",
                "HEADLINE_BLAST",
                "PUNCH_ZOOM",
                "SPEED_RAMP",
                "CAMERA_SHAKE",
                "FREEZE_FRAME",
                "HIGHLIGHT_PULSE",
                "GLITCH_TRANSITION",
                "SWIPE_TRANSITION",
                "BLUR_TRANSITION",
                "COUNTDOWN_EFFECT",
                "STAT_POP",
                "QUOTE_POP"
        ));
        contract.put("stylePriority", List.of("userSelectedStyle", "editorialJudgment", "styleSuggestions"));
        contract.put("timelinePlannerInputSchema", Map.of(
                "videoType", "editorial category",
                "selectedClip", "already-selected candidate clip",
                "transcript", "selected clip transcript nodes",
                "criticalScenes", "metadata.criticalScenes",
                "storyStructure", "metadata.storyStructure",
                "availableAssets", "known source/support assets",
                "styleSuggestions", "metadata.styleSuggestions",
                "userSelectedStyle", "optional style override"
        ));
        contract.put("timelineSchema", Map.of(
                "selectedStyle", "one allowed style",
                "needsAdditionalAssets", "boolean",
                "assetRequests", "array of {type, reason}",
                "timelineTracks", "array of {trackId, type, clips}",
                "clipSchema", "{clipId,start,end,layout,purpose,effects,assets}"
        ));
        contract.put("editorialMetadataSchema", Map.of(
                "videoType", "JOURNALISM|DOCUMENTARY|PODCAST_QA|FINANCE_EXPLAINER|EDUCATIONAL|STORYTELLING|VIRAL_SHORTS|INTERVIEW|COMMENTARY",
                "styleSuggestions", "array of {style, confidence}",
                "criticalScenes", "array of {sceneId,start,end,sceneType,importance,reason}",
                "storyStructure", "{hook, mainPoints, evidence, conclusion}",
                "retentionOpportunities", "array of {start,end,reason}",
                "assetOpportunities", "array of {start,end,assetType,description}"
        ));
        return contract;
    }

    private Map<String, Object> uiTimelineProjectAgentContract() {
        Map<String, Object> contract = new LinkedHashMap<>();
        contract.put("role", "UI Timeline Project Agent");
        contract.put("mission", "Convert the completed story and edit plan into a browser-editable video project definition.");
        contract.put("corePrinciple", "The project JSON is the source of truth; rendered videos are disposable outputs that can be regenerated from master video, timeline JSON, assets, and captions.");
        contract.put("nonResponsibilities", List.of(
                "video rendering",
                "MP4 generation",
                "FFmpeg command generation",
                "Remotion code generation",
                "effect implementation"
        ));
        contract.put("inputs", Map.of(
                "projectId", "stable editable project id",
                "masterVideo", "source asset backing all primary footage",
                "transcript", "selected clip transcript nodes",
                "captions", "captionPlan.captions",
                "timelinePlan", "metadata.timelinePlanner",
                "availableAssets", "master and supporting assets",
                "selectedStyle", "timelinePlanner.selectedStyle"
        ));
        contract.put("trackTypes", List.of(
                "PRIMARY_VIDEO",
                "SUPPORTING_VIDEO",
                "SCREENSHOTS",
                "DOCUMENTS",
                "MAPS",
                "CHARTS",
                "EFFECTS",
                "CAPTIONS",
                "AUDIO",
                "OVERLAYS"
        ));
        contract.put("layoutTypes", List.of(
                "FULL_SCREEN_SPEAKER",
                "DOUBLE_SCREEN_VERTICAL",
                "DOUBLE_SCREEN_HORIZONTAL",
                "FULL_FOOTAGE",
                "PICTURE_IN_PICTURE",
                "HEADLINE_CARD",
                "STAT_CARD",
                "EVIDENCE_VIEW",
                "SCREENSHOT_VIEW",
                "MAP_VIEW",
                "CONCLUSION_CARD"
        ));
        contract.put("effectTypes", List.of(
                "LIGHTNING_FLASH",
                "WHITE_FLASH",
                "HEADLINE_BLAST",
                "PUNCH_ZOOM",
                "SPEED_RAMP",
                "CAMERA_SHAKE",
                "FREEZE_FRAME",
                "HIGHLIGHT_PULSE",
                "GLITCH_TRANSITION",
                "SWIPE_TRANSITION",
                "BLUR_TRANSITION",
                "COUNTDOWN_EFFECT",
                "STAT_POP",
                "QUOTE_POP"
        ));
        contract.put("clipSchema", Map.of(
                "clipId", "stable clip id",
                "trackId", "track containing the clip",
                "start", "timeline start seconds",
                "end", "timeline end seconds",
                "layout", "one allowed UI layout",
                "purpose", "editorial purpose",
                "editable", true,
                "effects", "allowed effects array",
                "assets", "assets referenced by this clip"
        ));
        contract.put("outputSchema", Map.of(
                "project", "project metadata and editor capabilities",
                "tracks", "array of editable project tracks",
                "assets", "array of master/supporting/pending assets",
                "captions", "array of editable captions",
                "timelineVersions", "array of version history entries"
        ));
        return contract;
    }


    private Map<String, Object> interestingnessForPrompt(
            ShortInterestingnessScoringService.InterestingnessScoringResult result,
            ShortInterestingnessCriticService.InterestingnessCriticResult critic
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("evidenceBacked", result.evidenceBacked());
        payload.put("interestingness", result.interestingness());
        payload.put("interestingnessCritic", critic.interestingnessCritic());
        payload.put("criticMetadata", critic.metadata());
        payload.put("topMoments", result.topMoments().stream().limit(40).toList());
        payload.put("candidateWindows", result.candidateWindows().stream().limit(40).toList());
        payload.put("metadata", result.metadata());
        return payload;
    }

    private List<Map<String, Object>> plannerPromptSample(List<Map<String, Object>> value, int limit) {
        List<Map<String, Object>> safe = value == null ? List.of() : value;
        if (limit <= 0 || safe.isEmpty()) {
            return List.of();
        }
        if (safe.size() <= limit) {
            List<Map<String, Object>> copy = new ArrayList<>();
            for (Map<String, Object> item : safe) {
                copy.add(item == null ? new LinkedHashMap<>() : new LinkedHashMap<>(item));
            }
            return copy;
        }

        Set<Integer> indexes = new java.util.TreeSet<>();
        int edgeCount = Math.min(Math.max(4, limit / 6), 40);
        for (int index = 0; index < edgeCount && index < safe.size(); index++) {
            indexes.add(index);
        }
        for (int index = Math.max(0, safe.size() - edgeCount); index < safe.size(); index++) {
            indexes.add(index);
        }
        int remaining = Math.max(0, limit - indexes.size());
        int bodyStart = Math.min(edgeCount, safe.size());
        int bodyEnd = Math.max(bodyStart, safe.size() - edgeCount);
        if (remaining > 0 && bodyEnd > bodyStart) {
            for (int slot = 0; slot < remaining; slot++) {
                double ratio = (slot + 0.5) / Math.max(1.0, remaining);
                int sampledIndex = bodyStart + (int) Math.floor(ratio * (bodyEnd - bodyStart));
                indexes.add(Math.max(0, Math.min(safe.size() - 1, sampledIndex)));
            }
        }

        List<Map<String, Object>> sampled = new ArrayList<>();
        for (Integer index : indexes) {
            if (sampled.size() >= limit) {
                break;
            }
            Map<String, Object> item = safe.get(index);
            sampled.add(item == null ? new LinkedHashMap<>() : new LinkedHashMap<>(item));
        }
        return sampled;
    }

    private Map<String, Object> graphForPlanner(Map<String, Object> graph) {
        Map<String, Object> copy = mutableMap(graph);
        List<Map<String, Object>> nodes = listOfMaps(copy.get("nodes"));
        List<Map<String, Object>> edges = listOfMaps(copy.get("edges"));
        copy.put("nodes", plannerPromptSample(nodes, MAX_PLANNER_GRAPH_NODES));
        copy.put("edges", plannerPromptSample(edges, MAX_PLANNER_GRAPH_EDGES));
        Map<String, Object> metadata = mapValue(copy.get("metadata"));
        metadata.put("promptCompacted", nodes.size() > MAX_PLANNER_GRAPH_NODES || edges.size() > MAX_PLANNER_GRAPH_EDGES);
        metadata.put("sourceNodeCount", nodes.size());
        metadata.put("sourceEdgeCount", edges.size());
        metadata.put("promptNodeCount", listOfMaps(copy.get("nodes")).size());
        metadata.put("promptEdgeCount", listOfMaps(copy.get("edges")).size());
        copy.put("metadata", metadata);
        return copy;
    }

    private Map<String, Object> transcriptPreviewForJob(List<Map<String, Object>> transcript, String source) {
        List<Map<String, Object>> safe = transcript == null ? List.of() : transcript;
        List<Map<String, Object>> previewNodes = new ArrayList<>();
        for (Map<String, Object> node : plannerPromptSample(safe, MAX_JOB_OUTPUT_TRANSCRIPT_PREVIEW_NODES)) {
            previewNodes.add(compactTranscriptNodeForJob(node));
        }
        double timelineEnd = 0.0;
        for (Map<String, Object> node : safe) {
            double start = doubleValue(node == null ? null : firstNonEmpty(node.get("start"), node.get("sourceStart")), 0.0);
            double end = doubleValue(node == null ? null : firstNonEmpty(node.get("end"), node.get("sourceEnd")), start);
            timelineEnd = Math.max(timelineEnd, end);
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", defaultString(source, "job_transcript_preview"));
        metadata.put("nodeCount", safe.size());
        metadata.put("previewNodeCount", previewNodes.size());
        metadata.put("compacted", safe.size() > previewNodes.size());

        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("source", defaultString(source, "job_transcript_preview"));
        preview.put("nodes", previewNodes);
        preview.put("timelineStart", 0);
        preview.put("timelineEnd", roundSeconds(timelineEnd));
        preview.put("metadata", metadata);
        return preview;
    }

    private Map<String, Object> compactTranscriptNodeForJob(Map<String, Object> sourceNode) {
        Map<String, Object> node = new LinkedHashMap<>();
        Map<String, Object> source = sourceNode == null ? new LinkedHashMap<>() : sourceNode;
        double start = roundSeconds(doubleValue(firstNonEmpty(source.get("start"), source.get("sourceStart")), 0.0));
        double end = roundSeconds(Math.max(start + 0.1, doubleValue(firstNonEmpty(source.get("end"), source.get("sourceEnd")), start + 1.0)));
        node.put("id", defaultString(source.get("id"), ""));
        node.put("start", start);
        node.put("end", end);
        node.put("time", timeLabel((int) Math.round(start), (int) Math.round(end)));
        node.put("speaker", defaultString(source.get("speaker"), "Speaker"));
        node.put("transcript", truncate(defaultString(firstNonEmpty(source.get("transcript"), source.get("text")), ""), 220));
        node.put("sceneId", defaultString(source.get("sceneId"), ""));
        node.put("interestingness", intValue(firstNonEmpty(source.get("interestingness"), source.get("interestingnessScore")), 0));
        return node;
    }

    private Map<String, Object> graphPreviewForJob(Map<String, Object> graph) {
        Map<String, Object> safe = mutableMap(graph);
        List<Map<String, Object>> nodes = listOfMaps(safe.get("nodes"));
        List<Map<String, Object>> edges = listOfMaps(safe.get("edges"));
        List<Map<String, Object>> previewNodes = new ArrayList<>();
        for (Map<String, Object> node : plannerPromptSample(nodes, MAX_JOB_OUTPUT_GRAPH_PREVIEW_NODES)) {
            previewNodes.add(compactGraphNodeForJob(node));
        }
        List<Map<String, Object>> previewEdges = new ArrayList<>();
        for (Map<String, Object> edge : plannerPromptSample(edges, MAX_JOB_OUTPUT_GRAPH_PREVIEW_EDGES)) {
            previewEdges.add(compactGraphEdgeForJob(edge));
        }

        Map<String, Object> metadata = mapValue(safe.get("metadata"));
        metadata.put("nodeCount", nodes.size());
        metadata.put("edgeCount", edges.size());
        metadata.put("previewNodeCount", previewNodes.size());
        metadata.put("previewEdgeCount", previewEdges.size());
        metadata.put("compacted", nodes.size() > previewNodes.size() || edges.size() > previewEdges.size());
        metadata.put("source", defaultString(metadata.get("source"), defaultString(safe.get("analysisSource"), "job_graph_preview")));

        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("analysisSource", defaultString(safe.get("analysisSource"), "job_graph_preview"));
        preview.put("graphViews", firstNonEmpty(safe.get("graphViews"), List.of("Conversation Graph", "Story Graph", "Scene Graph")));
        preview.put("nodes", previewNodes);
        preview.put("edges", previewEdges);
        preview.put("metadata", metadata);
        return preview;
    }

    private Map<String, Object> compactGraphNodeForJob(Map<String, Object> sourceNode) {
        Map<String, Object> source = sourceNode == null ? new LinkedHashMap<>() : sourceNode;
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", defaultString(source.get("id"), ""));
        node.put("label", truncate(defaultString(source.get("label"), ""), 120));
        node.put("type", defaultString(source.get("type"), "NODE"));
        if (source.containsKey("start")) {
            node.put("start", source.get("start"));
        }
        if (source.containsKey("end")) {
            node.put("end", source.get("end"));
        }
        if (source.containsKey("speaker")) {
            node.put("speaker", source.get("speaker"));
        }
        if (source.containsKey("sceneId")) {
            node.put("sceneId", source.get("sceneId"));
        }
        if (source.containsKey("interestingness")) {
            node.put("interestingness", source.get("interestingness"));
        }
        if (source.containsKey("representativeFrameId")) {
            node.put("representativeFrameId", source.get("representativeFrameId"));
        }
        return node;
    }

    private Map<String, Object> compactGraphEdgeForJob(Map<String, Object> sourceEdge) {
        Map<String, Object> source = sourceEdge == null ? new LinkedHashMap<>() : sourceEdge;
        Map<String, Object> edge = new LinkedHashMap<>();
        edge.put("from", defaultString(source.get("from"), ""));
        edge.put("to", defaultString(source.get("to"), ""));
        edge.put("type", defaultString(source.get("type"), "EDGE"));
        edge.put("reason", truncate(defaultString(source.get("reason"), ""), 160));
        return edge;
    }

    private Map<String, Object> fullTranscriptMetadataForPrompt(Map<String, Object> metadata) {
        Map<String, Object> copy = mutableMap(metadata);
        copy.remove("chunks");
        return copy;
    }

    private Map<String, Object> storyCriticMetadataForPrompt(Map<String, Object> metadata) {
        Map<String, Object> copy = mutableMap(metadata);
        copy.remove("rawTextPreview");
        return copy;
    }

    private Map<String, Object> sceneCriticMetadataForPrompt(Map<String, Object> metadata) {
        Map<String, Object> copy = mutableMap(metadata);
        copy.remove("rawTextPreview");
        return copy;
    }

    private Map<String, Object> videoTypeCriticMetadataForPrompt(Map<String, Object> metadata) {
        Map<String, Object> copy = mutableMap(metadata);
        copy.remove("rawTextPreview");
        return copy;
    }

    private Map<String, Object> transcriptCriticMetadataForPrompt(Map<String, Object> metadata) {
        Map<String, Object> copy = mutableMap(metadata);
        copy.remove("chunkAudits");
        return copy;
    }

    private List<Map<String, Object>> transcriptCriticAuditsForPrompt(List<Map<String, Object>> audits) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (audits == null) {
            return result;
        }
        for (Map<String, Object> audit : audits) {
            Map<String, Object> copy = new LinkedHashMap<>(audit);
            copy.remove("rawTextPreview");
            result.add(copy);
            if (result.size() >= 80) {
                break;
            }
        }
        return result;
    }

    private List<Map<String, Object>> frameMetadataForPrompt(List<Map<String, Object>> frames) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (frames == null) {
            return result;
        }
        for (Map<String, Object> frame : frames) {
            Map<String, Object> copy = new LinkedHashMap<>(frame);
            copy.remove("thumbnailDataUrl");
            result.add(copy);
        }
        return result;
    }
    private String renderShortsPrompt(Map<String, Object> variables) {
        return """
                You are Short Video Cursor, a backend agent that turns a long creator video into reusable short-video candidates.
                Return only one valid JSON object. Do not include markdown.

                Source context:
                %s

                Media-backed preprocessing has already run before this planner:
                - Google video sample understanding for early TRANSCRIPT / VIDEO_TYPE_CLASSIFICATION.
                - Full transcript worker over extracted chunked audio.
                - Deep transcript critic comparing chunked source audio against the transcript nodes.
                - Full-video type critic validating or correcting VIDEO_TYPE_CLASSIFICATION from temporal media evidence across the source.
                - VIDEO_ANALYSIS runs Gemini against the uploaded source video as a whole-video reference; when the source is long, it uses sequential 10-minute video windows and durable checkpoints. Provider calls are intentionally not parallelized because of rate limits.
                - SCENE_ANALYSIS attaches transcript nodes to Gemini video scene windows. Backend frame-by-frame extraction is skipped unless a later renderer needs local frames.
                - SCENE_CRITIC reuses the full-video Gemini scene map as media-backed visual evidence; it does not run an additional frame-image critic when the scene map is available.
                - Independent story critic that builds story understanding, checks causality/context, and produces chain preservation rules.
                - Robust deterministic interestingness scoring with per-node scorecards, risk penalties, and ranked candidate windows.
                - Independent interestingness critic validating score distribution, scorecards, evidence, ranking uniqueness, context safety, visual safety, and compression readiness.
                - Story beat planning creates source-grounded short intents, beat plans, hook promises, and exact graph-cut EDL seeds.
                - Visual story composition enforces narrator anchor, B-roll/support balance, face/speaker presence, and visual variety when source evidence exists.
                - Production hook generation runs after compression planning from exact EDL segments; planner hook output is advisory only.
                - Deterministic graph builder from full transcript plus scene data.

                Treat fullTranscriptWorker.transcript, sceneAnalysis.transcript, deepTranscriptCritic, deepVideoTypeCritic.videoDna, deepSceneCritic, storyUnderstanding, deepStoryCritic, robustInterestingness, robustInterestingness.interestingnessCritic, and realGraph as compact source-grounded prompt views. Do not replace real transcript, real graph, or robust interestingness rankings with invented placeholders; use deepTranscriptCritic to avoid unsafe cuts, deepVideoTypeCritic to choose the correct story/compression strategy, deepSceneCritic to avoid bad visual boundaries, deepStoryCritic to preserve story causality, context prerequisites, setup-payoff chains, and question-answer pairs, robustInterestingness.candidateWindows as the primary ranked seed list for short candidates, and robustInterestingness.interestingnessCritic.acceptedWindowIds / repairActions to avoid unsafe or weak interestingness windows. If plannerCompaction.enabled is true, use candidate windows, top moments, exact node ids, and graph metadata to stay grounded in the full database timeline.

                Timeline Planner Agent:
                - You are the AI Timeline Planning Agent for each already-selected candidate story. Do not select a different story and do not rewrite the story.
                - You are not a renderer. Do not generate FFmpeg commands, Remotion code, coordinates, or implementation details.
                - Use timelinePlannerAgent.styles, timelinePlannerAgent.layoutTypes, and timelinePlannerAgent.effectTypes exactly; do not invent new layout or effect types.
                - For each candidate, add metadata.timelinePlanner with: selectedStyle, needsAdditionalAssets, assetRequests, and timelineTracks.
                - Each metadata.timelinePlanner.timelineTracks item must be {trackId,type,clips}. Each clip must be {clipId,start,end,layout,purpose,effects,assets}. Keep start/end relative to the candidate timeline, not absolute source time.
                - Hook the viewer within the first 3 seconds, avoid long talking-head-only stretches, prefer visual changes every 2-5 seconds when it supports the story, and use effects intentionally around hooks, reveals, claims, evidence, quotes, statistics, or energy drops.
                - Prefer actual footage over talking head, prefer evidence when evidence exists, and prefer screenshots/maps/charts when they improve understanding.
                - For JOURNALISM_DOUBLE_SCREEN, frequently use FULL_SCREEN_SPEAKER, DOUBLE_SCREEN, FULL_FOOTAGE, and EVIDENCE_VIEW in a Speaker -> Footage -> Double Screen -> Evidence -> Speaker rhythm.
                - If userSelectedStyle exists inside timelinePlannerAgent or request notes, it wins over styleSuggestions and your editorial judgment.
                - Request additional assets only when they materially improve the edit; otherwise set needsAdditionalAssets=false.
                - Do not modify clip generation logic for this metadata. The selected clip, EDL segments, captions, and render manifest stay governed by the existing candidate logic.
                - In addition to existing candidate metadata, add these fields at metadata root for every candidate: videoType, styleSuggestions, criticalScenes, storyStructure, retentionOpportunities, assetOpportunities.

                UI Timeline Project Agent:
                - After metadata.timelinePlanner is defined, add metadata.uiTimelineProject that can be loaded directly by a browser-based video editor.
                - The project JSON is the source of truth. Do not depend on previously rendered videos; the editor must reconstruct from master video, timeline JSON, assets, and captions.
                - You are not a renderer. Do not generate MP4 files, FFmpeg commands, Remotion code, or effect implementation details.
                - Use uiTimelineProjectAgent.trackTypes, uiTimelineProjectAgent.layoutTypes, and uiTimelineProjectAgent.effectTypes exactly.
                - metadata.uiTimelineProject must contain: project, tracks, assets, captions, timelineVersions. It may also include needsAdditionalAssets and assetRequests.
                - Every timeline clip must include {clipId,trackId,start,end,layout,purpose,editable,regeneratable,effects,assets}. Set editable=true and regeneratable=true.
                - For DOUBLE_SCREEN_VERTICAL, explicitly define topPane and bottomPane. For DOUBLE_SCREEN_HORIZONTAL, explicitly define leftPane and rightPane. Pane objects must identify source, clipStart/clipEnd or assetId, and role.
                - Support page refresh, reopening, undo, redo, version history, drag/resize, move between tracks, effect editing, layout switching, asset replacement, AI regeneration, and final export through metadata only.

                Build the enhanced pipeline below as traceable agent output:
                INGESTION -> TRANSCRIPT -> TRANSCRIPT_CRITIC -> VIDEO_TYPE_CLASSIFICATION -> VIDEO_TYPE_CRITIC -> VIDEO_ANALYSIS -> CONVERSATION_STRUCTURE -> SCENE_ANALYSIS -> SCENE_CRITIC -> VIDEO_GRAPH_BUILDER -> STORY_UNDERSTANDING -> STORY_CRITIC -> INTERESTINGNESS -> INTERESTINGNESS_CRITIC -> STORY_BEAT_PLANNING -> VISUAL_STORY_COMPOSITION -> COMPRESSION -> COMPRESSION_CRITIC -> HOOK_GENERATION -> HOOK_CRITIC -> CAPTION_PLANNING -> CAPTION_CRITIC -> VISUAL_ENHANCEMENT -> VISUAL_CRITIC -> CONTINUITY_CRITIC -> CANDIDATE_RANKING -> GLOBAL_CRITIC -> TARGETED_REPAIR -> RENDERING -> POST_RENDER_QA -> COMPLETED.

                Required JSON schema:
                {
                  "videoDna": {
                    "primaryType": "podcast|interview|tutorial|sketch|documentary|reaction|gaming|presentation|review|vlog|unknown",
                    "confidence": 0.0,
                    "structureType": "qa|dramatic|instructional|storytelling|reveal|reaction|unknown",
                    "analysisSource": "upload_metadata|transcript|video_graph"
                  },
                  "transcript": [
                    {"id":"n-001","start":0,"end":12,"speaker":"Host","transcript":"...","sceneId":"scene-001","emotion":70,"motion":20,"interestingness":80,"interestingnessScorecard":{"status":"STRONG","dimensions":{}},"frames":["F01","F02"]}
                  ],
                  "graph": {
                    "graphViews":["Conversation Graph","Story Graph","Scene Graph","Compression Graph"],
                    "nodes":[{"id":"n-001","label":"Question","type":"QUESTION","start":0,"end":12}],
                    "edges":[{"from":"n-001","to":"n-002","type":"QUESTION_ANSWER","reason":"..."}]
                  },
                  "candidates": [
                    {
                      "title":"Short title",
                      "durationSeconds":60,
                      "score":94,
                      "hookType":"Question Hook",
                      "editDecisionList":{"strategy":"KEEP_QA_CHAIN","segments":[{"nodeId":"n-001","operation":"KEEP_QA_CHAIN","reason":"..."}]},
                      "captionPlan":{"style":"platform-aware","captions":[{"start":0,"end":3,"text":"source transcript phrase"}]},
                      "renderManifest":{"renderStatus":"PENDING_REVIEW","aspectRatio":"9:16","safeZones":["top_caption_safe","bottom_ui_safe"]},
                      "metadata":{
                        "chain":"Q1 + best answer + insight",
                        "videoType":"PODCAST_QA",
                        "styleSuggestions":[{"style":"PODCAST_QA","confidence":0.92}],
                        "criticalScenes":[{"sceneId":"scene_1","start":0,"end":3,"sceneType":"HOOK","importance":0.95,"reason":"strong opening question"}],
                        "storyStructure":{"hook":"scene_1","mainPoints":[],"evidence":[],"conclusion":"scene_1"},
                        "retentionOpportunities":[{"start":0,"end":3,"reason":"opening hook can use a strong pattern interrupt"}],
                        "assetOpportunities":[],
                        "thumbnailCandidates":[],
                        "abTests":[],
                        "timelinePlanner":{
                          "selectedStyle":"AUTO",
                          "needsAdditionalAssets":false,
                          "assetRequests":[],
                          "timelineTracks":[
                            {
                              "trackId":"primary",
                              "type":"video",
                              "clips":[{"clipId":"c1","start":0,"end":3,"layout":"FULL_SCREEN_SPEAKER","purpose":"HOOK","effects":["PUNCH_ZOOM"],"assets":[]}]
                            }
                          ]
                        },
                        "uiTimelineProject":{
                          "project":{"projectId":"project_1","source":"ui_timeline_project_agent","selectedStyle":"PODCAST_QA","sourceOfTruth":"project_json"},
                          "tracks":[
                            {
                              "trackId":"primary_video",
                              "type":"PRIMARY_VIDEO",
                              "clips":[{"clipId":"c1","trackId":"primary_video","start":0,"end":3,"layout":"FULL_SCREEN_SPEAKER","purpose":"HOOK","editable":true,"regeneratable":true,"effects":["PUNCH_ZOOM"],"assets":[]}]
                            }
                          ],
                          "assets":[{"assetId":"master_video","type":"master_video","source":"master_video"}],
                          "captions":[{"captionId":"cap_1","start":0,"end":3,"text":"source transcript phrase","editable":true,"regeneratable":true}],
                          "timelineVersions":[{"version":1,"createdBy":"AI","description":"Initial PODCAST_QA Edit"}],
                          "needsAdditionalAssets":false,
                          "assetRequests":[]
                        }
                      }
                    }
                  ],
                  "trace": [
                    {"stage":"VIDEO_TYPE_CLASSIFICATION","status":"COMPLETED","summary":"...","confidence":0.91,"decision":"podcast"}
                  ]
                }

                If you cannot inspect actual media/transcript from the provided source metadata, still return useful provisional candidates, but set videoDna.analysisSource="upload_metadata" and explain the limitation in trace. Produce exactly requestedShorts candidates unless impossible. Keep every decision traceable.
                """.formatted(toJson(variables));
    }

    private List<CreatorShortCandidate> saveCandidates(CreatorShortVideo video, List<Map<String, Object>> payloads, Map<String, Object> aiOutput) {
        int limit = Math.max(1, Math.min(video.getRequestedShorts() == null ? 20 : video.getRequestedShorts(), 50));
        List<CreatorShortCandidate> candidates = new ArrayList<>();
        for (int index = 0; index < Math.min(payloads.size(), limit); index++) {
            Map<String, Object> payload = payloads.get(index);
            int rank = index + 1;
            Map<String, Object> metadata = mapValue(payload.get("metadata"));
            metadata.putIfAbsent("source", "short_video_cursor");
            metadata.putIfAbsent("reviewMode", video.getReviewMode());
            Map<String, Object> editDecisionList = defaultMap(payload.get("editDecisionList"), fallbackEditDecisionList(rank));
            Map<String, Object> captionPlan = defaultMap(payload.get("captionPlan"), fallbackCaptionPlan(video));
            Map<String, Object> renderManifest = defaultMap(payload.get("renderManifest"), fallbackRenderManifest(video));
            enrichTimelinePlannerEditorialMetadata(video, payload, aiOutput, metadata, editDecisionList, captionPlan, renderManifest, rank);
            CreatorShortCandidate candidate = CreatorShortCandidate.builder()
                    .tenantId(video.getTenantId())
                    .userId(video.getUserId())
                    .videoId(video.getId())
                    .generationJobId(video.getGenerationJobId())
                    .rankIndex(rank)
                    .title(defaultString(payload.get("title"), "Candidate " + rank))
                    .durationSeconds(intValue(payload.get("durationSeconds"), video.getTargetDurationSeconds()))
                    .score(scoreValue(payload.get("score"), rank))
                    .hookType(defaultString(payload.get("hookType"), "Insight Hook"))
                    .status("READY_FOR_REVIEW")
                    .reviewStatus("PENDING")
                    .editDecisionList(editDecisionList)
                    .captionPlan(captionPlan)
                    .renderManifest(renderManifest)
                    .metadata(metadata)
                    .build();
            candidates.add(candidateRepository.save(candidate));
        }
        return candidates;
    }

    private void enrichTimelinePlannerEditorialMetadata(
            CreatorShortVideo video,
            Map<String, Object> payload,
            Map<String, Object> aiOutput,
            Map<String, Object> metadata,
            Map<String, Object> editDecisionList,
            Map<String, Object> captionPlan,
            Map<String, Object> renderManifest,
            int rank
    ) {
        Map<String, Object> root = aiOutput == null ? Map.of() : aiOutput;
        Map<String, Object> rootVideoDna = mapValue(root.get("videoDna"));
        Map<String, Object> persistedVideoDna = mutableMap(video == null ? null : video.getVideoDna());
        String videoType = normalizeEditorialVideoType(
                firstNonEmpty(metadata.get("videoType"), payload.get("videoType"), root.get("videoType")),
                rootVideoDna,
                persistedVideoDna,
                video == null ? "" : video.getTitle(),
                payload.get("title"),
                payload.get("hookType"),
                editDecisionList.get("strategy"),
                metadata.get("chain")
        );
        metadata.put("videoType", videoType);

        List<Map<String, Object>> criticalScenes = timelineCriticalScenes(
                firstNonEmpty(metadata.get("criticalScenes"), payload.get("criticalScenes"), root.get("criticalScenes")),
                payload,
                editDecisionList,
                video,
                rank
        );
        List<Map<String, Object>> styleSuggestions = timelineStyleSuggestions(
                firstNonEmpty(metadata.get("styleSuggestions"), payload.get("styleSuggestions"), root.get("styleSuggestions")),
                videoType
        );
        metadata.put("styleSuggestions", styleSuggestions);
        metadata.put("criticalScenes", criticalScenes);
        metadata.put("storyStructure", timelineStoryStructure(
                firstNonEmpty(metadata.get("storyStructure"), payload.get("storyStructure"), root.get("storyStructure")),
                criticalScenes
        ));
        metadata.put("retentionOpportunities", timelineRetentionOpportunities(
                firstNonEmpty(metadata.get("retentionOpportunities"), payload.get("retentionOpportunities"), root.get("retentionOpportunities")),
                criticalScenes,
                videoType
        ));
        List<Map<String, Object>> assetOpportunities = timelineAssetOpportunities(
                firstNonEmpty(metadata.get("assetOpportunities"), payload.get("assetOpportunities"), root.get("assetOpportunities")),
                criticalScenes,
                videoType
        );
        metadata.put("assetOpportunities", assetOpportunities);
        Map<String, Object> timelinePlanner = timelinePlannerPlan(
                firstNonEmpty(metadata.get("timelinePlanner"), payload.get("timelinePlanner"), root.get("timelinePlanner")),
                styleSuggestions,
                criticalScenes,
                assetOpportunities,
                videoType
        );
        metadata.put("timelinePlanner", timelinePlanner);
        metadata.put("uiTimelineProject", uiTimelineProject(
                firstNonEmpty(metadata.get("uiTimelineProject"), payload.get("uiTimelineProject"), root.get("uiTimelineProject")),
                video,
                payload,
                metadata,
                captionPlan,
                renderManifest,
                timelinePlanner,
                rank
        ));
    }

    private String normalizeEditorialVideoType(
            Object explicit,
            Map<String, Object> rootVideoDna,
            Map<String, Object> persistedVideoDna,
            Object... hints
    ) {
        String normalizedExplicit = editorialToken(explicit);
        if (allowedEditorialVideoTypes().contains(normalizedExplicit)) {
            return normalizedExplicit;
        }
        String primary = editorialToken(firstNonEmpty(rootVideoDna.get("primaryType"), persistedVideoDna.get("primaryType")));
        String structure = editorialToken(firstNonEmpty(rootVideoDna.get("structureType"), persistedVideoDna.get("structureType")));
        StringBuilder text = new StringBuilder(primary).append(' ').append(structure);
        for (Object hint : hints == null ? new Object[0] : hints) {
            text.append(' ').append(defaultString(hint, ""));
        }
        String signal = text.toString().toLowerCase(Locale.ROOT);
        if (signal.contains("finance") || signal.contains("stock") || signal.contains("market") || signal.contains("invest") || signal.contains("revenue")) {
            return "FINANCE_EXPLAINER";
        }
        if (signal.contains("journal") || signal.contains("news") || signal.contains("investigation") || signal.contains("evidence")) {
            return "JOURNALISM";
        }
        if (signal.contains("documentary") || signal.contains("case study")) {
            return "DOCUMENTARY";
        }
        if (signal.contains("podcast") || signal.contains("qa") || signal.contains("question") || signal.contains("answer")) {
            return "PODCAST_QA";
        }
        if (signal.contains("interview")) {
            return "INTERVIEW";
        }
        if (signal.contains("tutorial") || signal.contains("education") || signal.contains("instruction") || signal.contains("course")) {
            return "EDUCATIONAL";
        }
        if (signal.contains("commentary") || signal.contains("reaction") || signal.contains("review")) {
            return "COMMENTARY";
        }
        if (signal.contains("viral") || signal.contains("reveal") || signal.contains("surprise") || signal.contains("contrarian")) {
            return "VIRAL_SHORTS";
        }
        return "STORYTELLING";
    }

    private List<Map<String, Object>> timelineStyleSuggestions(Object explicit, String videoType) {
        List<Map<String, Object>> supplied = sanitizeStyleSuggestions(explicit);
        if (!supplied.isEmpty()) {
            return supplied;
        }
        return switch (defaultString(videoType, "STORYTELLING")) {
            case "JOURNALISM" -> List.of(styleSuggestion("JOURNALISM_DOUBLE_SCREEN", 0.92), styleSuggestion("DOCUMENTARY", 0.78), styleSuggestion("VIRAL_SHORTS", 0.7));
            case "DOCUMENTARY" -> List.of(styleSuggestion("DOCUMENTARY", 0.92), styleSuggestion("STORYTELLING", 0.82), styleSuggestion("JOURNALISM_DOUBLE_SCREEN", 0.64));
            case "PODCAST_QA", "INTERVIEW" -> List.of(styleSuggestion("PODCAST_QA", 0.92), styleSuggestion("JOURNALISM_DOUBLE_SCREEN", 0.74), styleSuggestion("VIRAL_SHORTS", 0.66));
            case "FINANCE_EXPLAINER" -> List.of(styleSuggestion("FINANCE_EXPLAINER", 0.94), styleSuggestion("EDUCATIONAL", 0.82), styleSuggestion("JOURNALISM_DOUBLE_SCREEN", 0.68));
            case "EDUCATIONAL" -> List.of(styleSuggestion("EDUCATIONAL", 0.92), styleSuggestion("STORYTELLING", 0.74), styleSuggestion("VIRAL_SHORTS", 0.62));
            case "VIRAL_SHORTS" -> List.of(styleSuggestion("VIRAL_SHORTS", 0.92), styleSuggestion("JOURNALISM_DOUBLE_SCREEN", 0.68), styleSuggestion("STORYTELLING", 0.62));
            case "COMMENTARY" -> List.of(styleSuggestion("VIRAL_SHORTS", 0.82), styleSuggestion("PODCAST_QA", 0.72), styleSuggestion("STORYTELLING", 0.68));
            default -> List.of(styleSuggestion("STORYTELLING", 0.86), styleSuggestion("VIRAL_SHORTS", 0.68), styleSuggestion("DOCUMENTARY", 0.62));
        };
    }

    private List<Map<String, Object>> sanitizeStyleSuggestions(Object explicit) {
        List<Map<String, Object>> suggestions = new ArrayList<>();
        if (explicit instanceof List<?> list) {
            for (Object item : list) {
                Map<String, Object> map = mapValue(item);
                String style = normalizeTimelineStyle(firstNonEmpty(map.get("style"), item instanceof String ? item : null));
                if (style.isBlank()) {
                    continue;
                }
                suggestions.add(styleSuggestion(style, doubleValue(map.get("confidence"), 0.7)));
                if (suggestions.size() >= 4) {
                    break;
                }
            }
        }
        return suggestions;
    }

    private List<Map<String, Object>> timelineCriticalScenes(
            Object explicit,
            Map<String, Object> payload,
            Map<String, Object> editDecisionList,
            CreatorShortVideo video,
            int rank
    ) {
        List<Map<String, Object>> supplied = sanitizeCriticalScenes(explicit);
        if (!supplied.isEmpty()) {
            return supplied;
        }
        List<Map<String, Object>> scenes = new ArrayList<>();
        List<Map<String, Object>> segments = listOfMaps(editDecisionList.get("segments"));
        double duration = Math.max(3.0, doubleValue(firstNonEmpty(payload.get("durationSeconds"), video == null ? null : video.getTargetDurationSeconds()), 60.0));
        if (segments.isEmpty()) {
            scenes.add(criticalScene("scene_%d_1".formatted(rank), 0, Math.min(3.0, duration), "HOOK", 0.95, "opening hook"));
            if (duration > 8.0) {
                scenes.add(criticalScene("scene_%d_2".formatted(rank), Math.min(3.0, duration - 1.0), Math.min(duration, Math.max(6.0, duration * 0.55)), "CLAIM", 0.82, "main selected story point"));
            }
            scenes.add(criticalScene("scene_%d_3".formatted(rank), Math.max(0.0, duration - 4.0), duration, "CONCLUSION", 0.78, "closing takeaway"));
            return compactCriticalScenes(scenes);
        }

        double cursor = 0.0;
        int max = Math.min(segments.size(), 8);
        for (int index = 0; index < max; index++) {
            Map<String, Object> segment = segments.get(index);
            double sourceDuration = segmentDuration(segment, 3.0);
            double start = doubleValue(firstNonEmpty(segment.get("timelineStart"), segment.get("start")), cursor);
            double end = doubleValue(firstNonEmpty(segment.get("timelineEnd"), segment.get("end")), start + sourceDuration);
            if (end <= start + 0.1) {
                end = start + Math.max(0.5, sourceDuration);
            }
            cursor = Math.max(cursor, end);
            String sceneType = criticalSceneTypeFor(segment, payload, index, segments.size());
            scenes.add(criticalScene(
                    defaultString(firstNonEmpty(segment.get("sceneId"), segment.get("id")), "scene_%d_%d".formatted(rank, index + 1)),
                    start,
                    end,
                    sceneType,
                    criticalImportance(sceneType, index, segments.size()),
                    criticalReason(segment, sceneType)
            ));
        }
        return compactCriticalScenes(scenes);
    }

    private List<Map<String, Object>> sanitizeCriticalScenes(Object explicit) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> item : listOfMaps(explicit)) {
            String sceneId = defaultString(firstNonEmpty(item.get("sceneId"), item.get("id")), "scene_" + (result.size() + 1));
            double start = Math.max(0.0, doubleValue(item.get("start"), result.size() * 3.0));
            double end = Math.max(start + 0.25, doubleValue(item.get("end"), start + 3.0));
            String type = normalizeCriticalSceneType(item.get("sceneType"));
            result.add(criticalScene(sceneId, start, end, type, doubleValue(item.get("importance"), 0.75), defaultString(item.get("reason"), type.toLowerCase(Locale.ROOT).replace('_', ' '))));
            if (result.size() >= 10) {
                break;
            }
        }
        return result;
    }

    private Map<String, Object> timelineStoryStructure(Object explicit, List<Map<String, Object>> criticalScenes) {
        Map<String, Object> supplied = mapValue(explicit);
        if (!supplied.isEmpty()) {
            return supplied;
        }
        String hook = "";
        String conclusion = "";
        List<String> mainPoints = new ArrayList<>();
        List<String> evidence = new ArrayList<>();
        for (Map<String, Object> scene : criticalScenes == null ? List.<Map<String, Object>>of() : criticalScenes) {
            String id = defaultString(scene.get("sceneId"), "");
            String type = defaultString(scene.get("sceneType"), "").toUpperCase(Locale.ROOT);
            if (hook.isBlank() && "HOOK".equals(type)) {
                hook = id;
            }
            if ("CONCLUSION".equals(type)) {
                conclusion = id;
            }
            if (List.of("CLAIM", "REVEAL", "QUOTE", "REACTION").contains(type)) {
                mainPoints.add(id);
            }
            if (List.of("EVIDENCE", "PROOF", "STATISTIC").contains(type)) {
                evidence.add(id);
            }
        }
        if (hook.isBlank() && criticalScenes != null && !criticalScenes.isEmpty()) {
            hook = defaultString(criticalScenes.get(0).get("sceneId"), "");
        }
        if (conclusion.isBlank() && criticalScenes != null && !criticalScenes.isEmpty()) {
            conclusion = defaultString(criticalScenes.get(criticalScenes.size() - 1).get("sceneId"), "");
        }
        Map<String, Object> structure = new LinkedHashMap<>();
        structure.put("hook", hook);
        structure.put("mainPoints", mainPoints);
        structure.put("evidence", evidence);
        structure.put("conclusion", conclusion);
        return structure;
    }

    private List<Map<String, Object>> timelineRetentionOpportunities(Object explicit, List<Map<String, Object>> criticalScenes, String videoType) {
        List<Map<String, Object>> supplied = sanitizeWindowReasonList(explicit, "retention opportunity");
        if (!supplied.isEmpty()) {
            return supplied;
        }
        List<Map<String, Object>> opportunities = new ArrayList<>();
        for (Map<String, Object> scene : criticalScenes == null ? List.<Map<String, Object>>of() : criticalScenes) {
            String type = defaultString(scene.get("sceneType"), "");
            double start = doubleValue(scene.get("start"), 0.0);
            double end = doubleValue(scene.get("end"), start + 2.0);
            double duration = Math.max(0.0, end - start);
            if (duration >= 5.0) {
                opportunities.add(windowReason(Math.min(end - 0.5, start + 3.5), Math.min(end, start + 5.5), "long section can benefit from a visual pattern interrupt"));
            } else if (List.of("REVEAL", "STATISTIC", "CLAIM", "QUOTE").contains(type)) {
                opportunities.add(windowReason(start, end, type.toLowerCase(Locale.ROOT) + " moment can be emphasized for retention"));
            }
            if (opportunities.size() >= 5) {
                break;
            }
        }
        if (opportunities.isEmpty() && "VIRAL_SHORTS".equals(videoType)) {
            opportunities.add(windowReason(0, 3, "opening hook should receive the strongest retention treatment"));
        }
        return opportunities;
    }

    private List<Map<String, Object>> timelineAssetOpportunities(Object explicit, List<Map<String, Object>> criticalScenes, String videoType) {
        List<Map<String, Object>> supplied = sanitizeAssetOpportunities(explicit);
        if (!supplied.isEmpty()) {
            return supplied;
        }
        List<Map<String, Object>> opportunities = new ArrayList<>();
        for (Map<String, Object> scene : criticalScenes == null ? List.<Map<String, Object>>of() : criticalScenes) {
            String type = defaultString(scene.get("sceneType"), "");
            double start = doubleValue(scene.get("start"), 0.0);
            double end = doubleValue(scene.get("end"), start + 2.0);
            if ("STATISTIC".equals(type)) {
                opportunities.add(assetOpportunity(start, end, "chart", "visualize the statistic"));
            } else if (List.of("EVIDENCE", "PROOF").contains(type)) {
                opportunities.add(assetOpportunity(start, end, "screenshot", "show supporting proof or source document"));
            } else if ("CLAIM".equals(type) && List.of("JOURNALISM", "DOCUMENTARY", "COMMENTARY").contains(videoType)) {
                opportunities.add(assetOpportunity(start, end, "supporting_video", "show relevant footage during the claim"));
            }
            if (opportunities.size() >= 5) {
                break;
            }
        }
        if (opportunities.isEmpty() && "FINANCE_EXPLAINER".equals(videoType)) {
            opportunities.add(assetOpportunity(0, 5, "chart", "support the explanation with a simple number or trend visual"));
        }
        return opportunities;
    }

    private Map<String, Object> timelinePlannerPlan(
            Object explicit,
            List<Map<String, Object>> styleSuggestions,
            List<Map<String, Object>> criticalScenes,
            List<Map<String, Object>> assetOpportunities,
            String videoType
    ) {
        Map<String, Object> plan = sanitizeTimelinePlannerPlan(explicit, styleSuggestions, assetOpportunities, videoType);
        if (listOfMaps(plan.get("timelineTracks")).isEmpty()) {
            plan.put("timelineTracks", List.of(timelinePlannerTrack(
                    "primary",
                    timelinePlannerClips(criticalScenes, assetOpportunities, videoType)
            )));
        }
        if (listOfMaps(plan.get("assetRequests")).isEmpty() && assetOpportunities != null && !assetOpportunities.isEmpty()) {
            plan.put("assetRequests", assetRequestsFromOpportunities(assetOpportunities));
            plan.put("needsAdditionalAssets", true);
        }
        return plan;
    }

    private Map<String, Object> sanitizeTimelinePlannerPlan(
            Object explicit,
            List<Map<String, Object>> styleSuggestions,
            List<Map<String, Object>> assetOpportunities,
            String videoType
    ) {
        Map<String, Object> source = mapValue(explicit);
        List<Map<String, Object>> assetRequests = sanitizeAssetRequests(source.get("assetRequests"));
        List<Map<String, Object>> tracks = sanitizeTimelineTracks(source.get("timelineTracks"), videoType, assetOpportunities);
        if (tracks.isEmpty() && !listOfMaps(source.get("timeline")).isEmpty()) {
            tracks = List.of(timelinePlannerTrack("primary", sanitizeTimelineClips(source.get("timeline"), videoType, assetOpportunities)));
        }

        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("selectedStyle", selectedTimelineStyle(firstNonEmpty(source.get("selectedStyle"), source.get("style")), styleSuggestions, videoType));
        plan.put("needsAdditionalAssets", booleanValue(source.get("needsAdditionalAssets"), !assetRequests.isEmpty()));
        plan.put("assetRequests", assetRequests);
        plan.put("timelineTracks", tracks);
        return plan;
    }

    private List<Map<String, Object>> sanitizeTimelineTracks(Object explicit, String videoType, List<Map<String, Object>> assetOpportunities) {
        List<Map<String, Object>> tracks = new ArrayList<>();
        for (Map<String, Object> item : listOfMaps(explicit)) {
            List<Map<String, Object>> clips = sanitizeTimelineClips(item.get("clips"), videoType, assetOpportunities);
            if (clips.isEmpty()) {
                continue;
            }
            Map<String, Object> track = new LinkedHashMap<>();
            track.put("trackId", defaultString(firstNonEmpty(item.get("trackId"), item.get("id")), tracks.isEmpty() ? "primary" : "track_" + (tracks.size() + 1)));
            track.put("type", "video");
            track.put("clips", clips);
            tracks.add(track);
            if (tracks.size() >= 4) {
                break;
            }
        }
        return tracks;
    }

    private List<Map<String, Object>> sanitizeTimelineClips(Object explicit, String videoType, List<Map<String, Object>> assetOpportunities) {
        List<Map<String, Object>> clips = new ArrayList<>();
        for (Map<String, Object> item : listOfMaps(explicit)) {
            double start = Math.max(0.0, doubleValue(item.get("start"), clips.size() * 3.0));
            double end = Math.max(start + 0.25, doubleValue(item.get("end"), start + 3.0));
            List<Map<String, Object>> assets = sanitizeTimelineClipAssets(item.get("assets"));
            if (assets.isEmpty()) {
                assets = clipAssetsForWindow(assetOpportunities, start, end);
            }
            String purpose = normalizeTimelinePurpose(firstNonEmpty(item.get("purpose"), item.get("sceneType"), item.get("type")));
            String layout = normalizeTimelineLayout(firstNonEmpty(item.get("layout"), item.get("type")));
            if ("FULL_SCREEN_SPEAKER".equals(layout) && !assets.isEmpty() && !"HOOK".equals(purpose)) {
                layout = layoutForScene(purpose, videoType, clips.size(), assets);
            }
            clips.add(timelinePlannerClip(
                    defaultString(firstNonEmpty(item.get("clipId"), item.get("sceneId"), item.get("id")), "c" + (clips.size() + 1)),
                    start,
                    end,
                    layout,
                    purpose,
                    sanitizeTimelineEffects(item.get("effects")),
                    assets
            ));
            if (clips.size() >= 24) {
                break;
            }
        }
        return clips;
    }

    private List<Map<String, Object>> timelinePlannerClips(
            List<Map<String, Object>> criticalScenes,
            List<Map<String, Object>> assetOpportunities,
            String videoType
    ) {
        List<Map<String, Object>> sourceScenes = criticalScenes == null || criticalScenes.isEmpty()
                ? List.of(criticalScene("scene_1", 0, 3, "HOOK", 0.95, "opening hook"))
                : criticalScenes;
        List<Map<String, Object>> clips = new ArrayList<>();
        for (Map<String, Object> scene : sourceScenes) {
            String sceneType = normalizeCriticalSceneType(scene.get("sceneType"));
            double sceneStart = Math.max(0.0, doubleValue(scene.get("start"), clips.size() * 3.0));
            double sceneEnd = Math.max(sceneStart + 0.25, doubleValue(scene.get("end"), sceneStart + 3.0));
            double cursor = sceneStart;
            int scenePart = 1;
            while (cursor < sceneEnd && clips.size() < 24) {
                double maxSpan = clips.isEmpty() && cursor <= 0.25 ? 3.0 : 4.5;
                double clipEnd = Math.min(sceneEnd, cursor + maxSpan);
                if (clipEnd <= cursor + 0.1) {
                    clipEnd = Math.min(sceneEnd, cursor + 0.25);
                }
                List<Map<String, Object>> assets = clipAssetsForWindow(assetOpportunities, cursor, clipEnd);
                clips.add(timelinePlannerClip(
                        "c" + (clips.size() + 1),
                        cursor,
                        clipEnd,
                        layoutForScene(sceneType, videoType, clips.size(), assets),
                        sceneType,
                        effectsForScene(sceneType, clips.size()),
                        assets
                ));
                cursor = clipEnd;
                scenePart++;
                if (scenePart > 8) {
                    break;
                }
            }
        }
        return clips;
    }

    private Map<String, Object> timelinePlannerTrack(String trackId, List<Map<String, Object>> clips) {
        List<Map<String, Object>> safeClips = clips == null || clips.isEmpty()
                ? List.of(timelinePlannerClip("c1", 0, 3, "FULL_SCREEN_SPEAKER", "HOOK", List.of("PUNCH_ZOOM"), List.of()))
                : clips;
        Map<String, Object> track = new LinkedHashMap<>();
        track.put("trackId", defaultString(trackId, "primary"));
        track.put("type", "video");
        track.put("clips", safeClips);
        return track;
    }

    private Map<String, Object> timelinePlannerClip(
            String clipId,
            double start,
            double end,
            String layout,
            String purpose,
            List<String> effects,
            List<Map<String, Object>> assets
    ) {
        Map<String, Object> clip = new LinkedHashMap<>();
        clip.put("clipId", defaultString(clipId, "clip"));
        clip.put("start", roundSeconds(Math.max(0.0, start)));
        clip.put("end", roundSeconds(Math.max(start + 0.25, end)));
        clip.put("layout", normalizeTimelineLayout(layout));
        clip.put("purpose", normalizeTimelinePurpose(purpose));
        clip.put("effects", effects == null ? List.of() : effects.stream().map(this::normalizeTimelineEffect).filter(effect -> !effect.isBlank()).distinct().limit(4).toList());
        clip.put("assets", assets == null ? List.of() : assets);
        return clip;
    }

    private String selectedTimelineStyle(Object explicit, List<Map<String, Object>> styleSuggestions, String videoType) {
        String explicitStyle = normalizeTimelineStyle(explicit);
        if (!explicitStyle.isBlank()) {
            return explicitStyle;
        }
        for (Map<String, Object> suggestion : styleSuggestions == null ? List.<Map<String, Object>>of() : styleSuggestions) {
            String style = normalizeTimelineStyle(suggestion.get("style"));
            if (!style.isBlank() && !"AUTO".equals(style)) {
                return style;
            }
        }
        return switch (defaultString(videoType, "STORYTELLING")) {
            case "JOURNALISM" -> "JOURNALISM_DOUBLE_SCREEN";
            case "DOCUMENTARY" -> "DOCUMENTARY";
            case "PODCAST_QA", "INTERVIEW" -> "PODCAST_QA";
            case "FINANCE_EXPLAINER" -> "FINANCE_EXPLAINER";
            case "EDUCATIONAL" -> "EDUCATIONAL";
            case "VIRAL_SHORTS", "COMMENTARY" -> "VIRAL_SHORTS";
            default -> "STORYTELLING";
        };
    }

    private List<Map<String, Object>> sanitizeAssetRequests(Object explicit) {
        List<Map<String, Object>> requests = new ArrayList<>();
        for (Map<String, Object> item : listOfMaps(explicit)) {
            requests.add(assetRequest(
                    defaultString(firstNonEmpty(item.get("type"), item.get("assetType")), "supporting_video"),
                    defaultString(firstNonEmpty(item.get("reason"), item.get("description")), "support the edit with a relevant visual")
            ));
            if (requests.size() >= 8) {
                break;
            }
        }
        return requests;
    }

    private List<Map<String, Object>> assetRequestsFromOpportunities(List<Map<String, Object>> assetOpportunities) {
        List<Map<String, Object>> requests = new ArrayList<>();
        for (Map<String, Object> item : assetOpportunities == null ? List.<Map<String, Object>>of() : assetOpportunities) {
            requests.add(assetRequest(
                    defaultString(item.get("assetType"), "supporting_video"),
                    defaultString(item.get("description"), "support the edit with a relevant visual")
            ));
            if (requests.size() >= 8) {
                break;
            }
        }
        return requests;
    }

    private Map<String, Object> assetRequest(String type, String reason) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("type", normalizeAssetType(type));
        request.put("reason", truncate(defaultString(reason, "support the edit with a relevant visual"), 220));
        return request;
    }

    private List<Map<String, Object>> sanitizeTimelineClipAssets(Object explicit) {
        List<Map<String, Object>> assets = new ArrayList<>();
        for (Map<String, Object> item : listOfMaps(explicit)) {
            Map<String, Object> asset = new LinkedHashMap<>();
            asset.put("type", normalizeAssetType(firstNonEmpty(item.get("type"), item.get("assetType"))));
            asset.put("description", truncate(defaultString(firstNonEmpty(item.get("description"), item.get("reason"), item.get("label")), "supporting visual"), 220));
            if (item.containsKey("assetId")) {
                asset.put("assetId", item.get("assetId"));
            }
            assets.add(asset);
            if (assets.size() >= 4) {
                break;
            }
        }
        return assets;
    }

    private List<Map<String, Object>> clipAssetsForWindow(List<Map<String, Object>> assetOpportunities, double start, double end) {
        List<Map<String, Object>> assets = new ArrayList<>();
        for (Map<String, Object> opportunity : assetOpportunities == null ? List.<Map<String, Object>>of() : assetOpportunities) {
            double assetStart = doubleValue(opportunity.get("start"), start);
            double assetEnd = doubleValue(opportunity.get("end"), end);
            if (!rangesOverlap(start, end, assetStart, assetEnd)) {
                continue;
            }
            Map<String, Object> asset = new LinkedHashMap<>();
            asset.put("type", normalizeAssetType(opportunity.get("assetType")));
            asset.put("description", truncate(defaultString(opportunity.get("description"), "supporting visual"), 220));
            assets.add(asset);
            if (assets.size() >= 4) {
                break;
            }
        }
        return assets;
    }

    private boolean rangesOverlap(double leftStart, double leftEnd, double rightStart, double rightEnd) {
        return Math.max(leftStart, rightStart) < Math.min(leftEnd, rightEnd);
    }

    private String layoutForScene(String sceneType, String videoType, int index, List<Map<String, Object>> assets) {
        String type = normalizeCriticalSceneType(sceneType);
        if ("HOOK".equals(type)) {
            return "FULL_SCREEN_SPEAKER";
        }
        String assetLayout = layoutForAssets(assets);
        if (!assetLayout.isBlank()) {
            return assetLayout;
        }
        return switch (type) {
            case "EVIDENCE", "PROOF" -> "EVIDENCE_VIEW";
            case "STATISTIC" -> "STAT_CARD";
            case "CONCLUSION" -> "CONCLUSION_CARD";
            case "REVEAL" -> index % 2 == 0 ? "FULL_FOOTAGE" : "DOUBLE_SCREEN";
            case "QUOTE" -> List.of("PODCAST_QA", "INTERVIEW").contains(videoType) ? "FULL_SCREEN_SPEAKER" : "DOUBLE_SCREEN";
            case "REACTION" -> "PICTURE_IN_PICTURE";
            default -> switch (defaultString(videoType, "STORYTELLING")) {
                case "JOURNALISM" -> index % 2 == 0 ? "DOUBLE_SCREEN" : "FULL_FOOTAGE";
                case "DOCUMENTARY" -> index % 2 == 0 ? "FULL_FOOTAGE" : "DOUBLE_SCREEN";
                case "FINANCE_EXPLAINER" -> index % 2 == 0 ? "DOUBLE_SCREEN" : "STAT_CARD";
                default -> index % 3 == 1 ? "DOUBLE_SCREEN" : "FULL_SCREEN_SPEAKER";
            };
        };
    }

    private String layoutForAssets(List<Map<String, Object>> assets) {
        for (Map<String, Object> asset : assets == null ? List.<Map<String, Object>>of() : assets) {
            String type = normalizeAssetType(asset.get("type"));
            if (List.of("screenshot", "document").contains(type)) {
                return "SCREENSHOT_VIEW";
            }
            if ("map".equals(type)) {
                return "MAP_VIEW";
            }
            if ("chart".equals(type)) {
                return "STAT_CARD";
            }
            if (List.of("supporting_video", "actual_footage", "broll").contains(type)) {
                return "FULL_FOOTAGE";
            }
            if ("headline".equals(type)) {
                return "HEADLINE_CARD";
            }
        }
        return "";
    }

    private List<String> effectsForScene(String sceneType, int index) {
        String type = normalizeCriticalSceneType(sceneType);
        List<String> effects = new ArrayList<>();
        switch (type) {
            case "HOOK" -> effects.add("PUNCH_ZOOM");
            case "REVEAL" -> effects.add("LIGHTNING_FLASH");
            case "STATISTIC" -> effects.add("STAT_POP");
            case "QUOTE" -> effects.add("QUOTE_POP");
            case "EVIDENCE", "PROOF" -> effects.add("HIGHLIGHT_PULSE");
            case "CONCLUSION" -> effects.add("BLUR_TRANSITION");
            default -> {
                if (index > 0) {
                    effects.add("SWIPE_TRANSITION");
                }
            }
        }
        return effects.stream().map(this::normalizeTimelineEffect).filter(effect -> !effect.isBlank()).toList();
    }

    private List<String> sanitizeTimelineEffects(Object explicit) {
        List<String> effects = new ArrayList<>();
        if (explicit instanceof String text) {
            String effect = normalizeTimelineEffect(text);
            if (!effect.isBlank()) {
                effects.add(effect);
            }
            return effects;
        }
        for (Object item : objectList(explicit)) {
            String effect = normalizeTimelineEffect(item);
            if (!effect.isBlank()) {
                effects.add(effect);
            }
            if (effects.size() >= 4) {
                break;
            }
        }
        return effects.stream().distinct().toList();
    }

    private Map<String, Object> uiTimelineProject(
            Object explicit,
            CreatorShortVideo video,
            Map<String, Object> payload,
            Map<String, Object> metadata,
            Map<String, Object> captionPlan,
            Map<String, Object> renderManifest,
            Map<String, Object> timelinePlanner,
            int rank
    ) {
        Map<String, Object> source = mapValue(explicit);
        Map<String, Object> sourceProject = mapValue(source.get("project"));
        String videoType = defaultString(metadata.get("videoType"), "STORYTELLING");
        String selectedStyle = selectedTimelineStyle(
                firstNonEmpty(source.get("selectedStyle"), sourceProject.get("selectedStyle"), timelinePlanner.get("selectedStyle")),
                listOfMaps(metadata.get("styleSuggestions")),
                videoType
        );
        String aspectRatio = defaultString(firstNonEmpty(renderManifest.get("aspectRatio"), video == null ? null : aspectRatioFor(video.getPlatform())), "9:16");
        List<Map<String, Object>> captions = uiProjectCaptions(firstNonEmpty(source.get("captions"), captionPlan.get("captions")));
        List<Map<String, Object>> assetRequests = uiProjectAssetRequests(firstNonEmpty(source.get("assetRequests"), timelinePlanner.get("assetRequests")));
        List<Map<String, Object>> tracks = sanitizeUiProjectTracks(source.get("tracks"), aspectRatio);
        if (tracks.isEmpty()) {
            tracks = uiProjectTracksFromTimelinePlanner(timelinePlanner, captions, aspectRatio, renderManifest);
        }
        boolean needsAdditionalAssets = booleanValue(source.get("needsAdditionalAssets"), booleanValue(timelinePlanner.get("needsAdditionalAssets"), !assetRequests.isEmpty()));
        List<Map<String, Object>> assets = uiProjectAssets(video, source.get("assets"), tracks, assetRequests);

        Map<String, Object> project = uiProjectMetadata(sourceProject, video, payload, renderManifest, selectedStyle, rank);
        project.put("masterVideo", uiProjectMasterVideo(video));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("project", project);
        result.put("tracks", tracks);
        result.put("assets", assets);
        result.put("captions", captions);
        result.put("timelineVersions", uiProjectTimelineVersions(source.get("timelineVersions"), selectedStyle));
        result.put("needsAdditionalAssets", needsAdditionalAssets);
        result.put("assetRequests", assetRequests);
        return result;
    }

    private Map<String, Object> uiProjectMetadata(
            Map<String, Object> explicit,
            CreatorShortVideo video,
            Map<String, Object> payload,
            Map<String, Object> renderManifest,
            String selectedStyle,
            int rank
    ) {
        Map<String, Object> project = explicit == null ? new LinkedHashMap<>() : new LinkedHashMap<>(explicit);
        String videoId = video == null || video.getId() == null ? "video" : video.getId().toString();
        project.put("projectId", defaultString(firstNonEmpty(project.get("projectId"), project.get("id")), "short_project_" + videoId + "_" + rank));
        project.put("source", "ui_timeline_project_agent");
        project.put("sourceOfTruth", "project_json");
        project.put("videoId", videoId);
        project.put("generationJobId", video == null || video.getGenerationJobId() == null ? "" : video.getGenerationJobId().toString());
        project.put("candidateRank", rank);
        project.put("title", defaultString(firstNonEmpty(payload.get("title"), video == null ? null : video.getTitle()), "Short candidate " + rank));
        project.put("platform", defaultString(video == null ? null : video.getPlatform(), "SHORTS"));
        project.put("selectedStyle", selectedStyle);
        project.put("aspectRatio", defaultString(renderManifest.get("aspectRatio"), video == null ? "9:16" : aspectRatioFor(video.getPlatform())));
        project.put("reconstructableFrom", List.of("masterVideo", "timelineJson", "assets", "captions"));
        project.put("capabilities", uiProjectCapabilities());
        return project;
    }

    private Map<String, Object> uiProjectCapabilities() {
        Map<String, Object> capabilities = new LinkedHashMap<>();
        for (String capability : List.of(
                "pageRefresh",
                "reopeningProjects",
                "undo",
                "redo",
                "versionHistory",
                "timelineEditing",
                "clipDragging",
                "clipResizing",
                "moveClipsBetweenTracks",
                "replaceFootage",
                "replaceScreenshots",
                "addEffects",
                "removeEffects",
                "layoutSwitching",
                "assetReplacement",
                "aiRegeneration",
                "sectionRegeneration",
                "styleSwitching",
                "finalExport"
        )) {
            capabilities.put(capability, true);
        }
        return capabilities;
    }

    private Map<String, Object> uiProjectMasterVideo(CreatorShortVideo video) {
        Map<String, Object> master = new LinkedHashMap<>();
        master.put("source", "master_video");
        master.put("assetId", "master_video");
        master.put("sourceAssetId", video == null || video.getSourceAssetId() == null ? "" : video.getSourceAssetId().toString());
        master.put("videoId", video == null || video.getId() == null ? "" : video.getId().toString());
        master.put("role", "primary_source");
        return master;
    }

    private List<Map<String, Object>> uiProjectTracksFromTimelinePlanner(
            Map<String, Object> timelinePlanner,
            List<Map<String, Object>> captions,
            String aspectRatio,
            Map<String, Object> renderManifest
    ) {
        List<Map<String, Object>> primaryClips = new ArrayList<>();
        int clipIndex = 0;
        for (Map<String, Object> track : listOfMaps(timelinePlanner.get("timelineTracks"))) {
            for (Map<String, Object> clip : listOfMaps(track.get("clips"))) {
                primaryClips.add(uiProjectClipFromTimelineClip(clip, "primary_video", aspectRatio, clipIndex++));
            }
        }
        if (primaryClips.isEmpty()) {
            primaryClips.add(uiProjectClip("c1", "primary_video", 0, 3, "FULL_SCREEN_SPEAKER", "HOOK", true, true, List.of("PUNCH_ZOOM"), List.of()));
        }

        List<Map<String, Object>> tracks = new ArrayList<>();
        tracks.add(uiProjectTrack("primary_video", "PRIMARY_VIDEO", primaryClips));

        List<Map<String, Object>> supporting = new ArrayList<>();
        List<Map<String, Object>> screenshots = new ArrayList<>();
        List<Map<String, Object>> documents = new ArrayList<>();
        List<Map<String, Object>> maps = new ArrayList<>();
        List<Map<String, Object>> charts = new ArrayList<>();
        List<Map<String, Object>> effects = new ArrayList<>();
        for (Map<String, Object> clip : primaryClips) {
            for (Map<String, Object> asset : listOfMaps(clip.get("assets"))) {
                String trackType = uiProjectTrackTypeForAsset(asset.get("type"));
                Map<String, Object> assetClip = uiProjectAssetClip(clip, asset, trackType.toLowerCase(Locale.ROOT));
                switch (trackType) {
                    case "SCREENSHOTS" -> screenshots.add(assetClip);
                    case "DOCUMENTS" -> documents.add(assetClip);
                    case "MAPS" -> maps.add(assetClip);
                    case "CHARTS" -> charts.add(assetClip);
                    default -> supporting.add(assetClip);
                }
            }
            int effectIndex = 1;
            for (String effect : listOfStrings(clip.get("effects"))) {
                Map<String, Object> effectClip = uiProjectClip(
                        defaultString(clip.get("clipId"), "clip") + "_fx_" + effectIndex,
                        "effects",
                        doubleValue(clip.get("start"), 0.0),
                        doubleValue(clip.get("end"), 0.25),
                        defaultString(clip.get("layout"), "FULL_SCREEN_SPEAKER"),
                        "EFFECT",
                        true,
                        true,
                        List.of(effect),
                        List.of()
                );
                effectClip.put("effectType", normalizeTimelineEffect(effect));
                effects.add(effectClip);
                effectIndex++;
            }
        }
        addTrackIfNotEmpty(tracks, "supporting_video", "SUPPORTING_VIDEO", supporting);
        addTrackIfNotEmpty(tracks, "screenshots", "SCREENSHOTS", screenshots);
        addTrackIfNotEmpty(tracks, "documents", "DOCUMENTS", documents);
        addTrackIfNotEmpty(tracks, "maps", "MAPS", maps);
        addTrackIfNotEmpty(tracks, "charts", "CHARTS", charts);
        addTrackIfNotEmpty(tracks, "effects", "EFFECTS", effects);
        addTrackIfNotEmpty(tracks, "captions", "CAPTIONS", uiProjectCaptionTrackClips(captions));

        double duration = Math.max(uiProjectTimelineDuration(primaryClips), doubleValue(renderManifest.get("targetDurationSeconds"), 0.0));
        if (duration > 0.0) {
            tracks.add(uiProjectTrack("audio", "AUDIO", List.of(uiProjectClip("audio_master", "audio", 0, duration, "FULL_SCREEN_SPEAKER", "SOURCE_AUDIO", true, true, List.of(), List.of()))));
        }
        return tracks;
    }

    private List<Map<String, Object>> sanitizeUiProjectTracks(Object explicit, String aspectRatio) {
        List<Map<String, Object>> tracks = new ArrayList<>();
        for (Map<String, Object> item : listOfMaps(explicit)) {
            String trackType = normalizeUiProjectTrackType(item.get("type"));
            String trackId = defaultString(firstNonEmpty(item.get("trackId"), item.get("id")), trackType.toLowerCase(Locale.ROOT));
            List<Map<String, Object>> clips = new ArrayList<>();
            int index = 0;
            for (Map<String, Object> clip : listOfMaps(item.get("clips"))) {
                clips.add(sanitizeUiProjectClip(clip, trackId, aspectRatio, index++));
            }
            if (!clips.isEmpty()) {
                tracks.add(uiProjectTrack(trackId, trackType, clips));
            }
            if (tracks.size() >= 12) {
                break;
            }
        }
        return tracks;
    }

    private Map<String, Object> sanitizeUiProjectClip(Map<String, Object> source, String trackId, String aspectRatio, int index) {
        String clipId = defaultString(firstNonEmpty(source.get("clipId"), source.get("id")), trackId + "_clip_" + (index + 1));
        double start = Math.max(0.0, doubleValue(source.get("start"), index * 3.0));
        double end = Math.max(start + 0.25, doubleValue(source.get("end"), start + 3.0));
        String layout = normalizeUiProjectLayout(source.get("layout"), aspectRatio);
        List<Map<String, Object>> assets = uiClipAssets(source.get("assets"), clipId, layout);
        Map<String, Object> clip = uiProjectClip(
                clipId,
                defaultString(source.get("trackId"), trackId),
                start,
                end,
                layout,
                defaultString(source.get("purpose"), "CLAIM"),
                booleanValue(source.get("editable"), true),
                booleanValue(source.get("regeneratable"), true),
                sanitizeTimelineEffects(source.get("effects")),
                assets
        );
        copyUiPaneIfPresent(source, clip, "topPane");
        copyUiPaneIfPresent(source, clip, "bottomPane");
        copyUiPaneIfPresent(source, clip, "leftPane");
        copyUiPaneIfPresent(source, clip, "rightPane");
        ensureDoubleScreenPanes(clip);
        return clip;
    }

    private Map<String, Object> uiProjectClipFromTimelineClip(Map<String, Object> source, String trackId, String aspectRatio, int index) {
        String clipId = defaultString(firstNonEmpty(source.get("clipId"), source.get("id"), source.get("sceneId")), "c" + (index + 1));
        double start = Math.max(0.0, doubleValue(source.get("start"), index * 3.0));
        double end = Math.max(start + 0.25, doubleValue(source.get("end"), start + 3.0));
        String layout = normalizeUiProjectLayout(source.get("layout"), aspectRatio);
        List<Map<String, Object>> assets = uiClipAssets(source.get("assets"), clipId, layout);
        Map<String, Object> clip = uiProjectClip(
                clipId,
                trackId,
                start,
                end,
                layout,
                defaultString(source.get("purpose"), "CLAIM"),
                true,
                true,
                sanitizeTimelineEffects(source.get("effects")),
                assets
        );
        clip.put("source", "master_video");
        clip.put("clipStart", roundSeconds(start));
        clip.put("clipEnd", roundSeconds(end));
        ensureDoubleScreenPanes(clip);
        return clip;
    }

    private Map<String, Object> uiProjectClip(
            String clipId,
            String trackId,
            double start,
            double end,
            String layout,
            String purpose,
            boolean editable,
            boolean regeneratable,
            List<String> effects,
            List<Map<String, Object>> assets
    ) {
        Map<String, Object> clip = new LinkedHashMap<>();
        clip.put("clipId", defaultString(clipId, "clip"));
        clip.put("trackId", defaultString(trackId, "primary_video"));
        clip.put("start", roundSeconds(Math.max(0.0, start)));
        clip.put("end", roundSeconds(Math.max(start + 0.25, end)));
        clip.put("layout", normalizeUiProjectLayout(layout, "9:16"));
        clip.put("purpose", defaultString(purpose, "CLAIM"));
        clip.put("editable", editable);
        clip.put("regeneratable", regeneratable);
        clip.put("effects", effects == null ? List.of() : effects.stream().map(this::normalizeTimelineEffect).filter(effect -> !effect.isBlank()).distinct().limit(4).toList());
        clip.put("assets", assets == null ? List.of() : assets);
        return clip;
    }

    private Map<String, Object> uiProjectTrack(String trackId, String type, List<Map<String, Object>> clips) {
        Map<String, Object> track = new LinkedHashMap<>();
        track.put("trackId", defaultString(trackId, "track"));
        track.put("type", normalizeUiProjectTrackType(type));
        track.put("clips", clips == null ? List.of() : clips);
        track.put("editable", true);
        return track;
    }

    private void addTrackIfNotEmpty(List<Map<String, Object>> tracks, String trackId, String type, List<Map<String, Object>> clips) {
        if (clips != null && !clips.isEmpty()) {
            tracks.add(uiProjectTrack(trackId, type, clips));
        }
    }

    private Map<String, Object> uiProjectAssetClip(Map<String, Object> parentClip, Map<String, Object> asset, String trackId) {
        Map<String, Object> clip = uiProjectClip(
                defaultString(parentClip.get("clipId"), "clip") + "_asset_" + defaultString(asset.get("assetId"), "asset"),
                trackId,
                doubleValue(parentClip.get("start"), 0.0),
                doubleValue(parentClip.get("end"), 0.25),
                layoutForAssetType(asset.get("type")),
                "SUPPORTING_ASSET",
                true,
                true,
                List.of(),
                List.of(asset)
        );
        clip.put("source", "supporting_asset");
        clip.put("assetId", defaultString(asset.get("assetId"), ""));
        return clip;
    }

    private List<Map<String, Object>> uiProjectCaptionTrackClips(List<Map<String, Object>> captions) {
        List<Map<String, Object>> clips = new ArrayList<>();
        for (Map<String, Object> caption : captions == null ? List.<Map<String, Object>>of() : captions) {
            String id = defaultString(caption.get("captionId"), "caption_" + (clips.size() + 1));
            clips.add(uiProjectClip(
                    id,
                    "captions",
                    doubleValue(caption.get("start"), clips.size() * 2.0),
                    doubleValue(caption.get("end"), clips.size() * 2.0 + 2.0),
                    "FULL_SCREEN_SPEAKER",
                    "CAPTION",
                    true,
                    true,
                    List.of(),
                    List.of()
            ));
        }
        return clips;
    }

    private List<Map<String, Object>> uiProjectCaptions(Object explicit) {
        List<Map<String, Object>> captions = new ArrayList<>();
        for (Map<String, Object> item : listOfMaps(explicit)) {
            double start = Math.max(0.0, doubleValue(item.get("start"), captions.size() * 2.0));
            double end = Math.max(start + 0.25, doubleValue(item.get("end"), start + 2.0));
            String text = truncate(defaultString(firstNonEmpty(item.get("text"), item.get("caption"), item.get("line")), ""), 220);
            if (text.isBlank()) {
                continue;
            }
            Map<String, Object> caption = new LinkedHashMap<>();
            caption.put("captionId", defaultString(firstNonEmpty(item.get("captionId"), item.get("id")), "cap_" + (captions.size() + 1)));
            caption.put("start", roundSeconds(start));
            caption.put("end", roundSeconds(end));
            caption.put("text", text);
            caption.put("editable", true);
            caption.put("regeneratable", true);
            caption.put("trackId", "captions");
            captions.add(caption);
            if (captions.size() >= 120) {
                break;
            }
        }
        return captions;
    }

    private List<Map<String, Object>> uiProjectAssetRequests(Object explicit) {
        List<Map<String, Object>> requests = new ArrayList<>();
        for (Map<String, Object> item : listOfMaps(explicit)) {
            String type = normalizeAssetType(firstNonEmpty(item.get("type"), item.get("assetType")));
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("requestId", defaultString(firstNonEmpty(item.get("requestId"), item.get("id")), "asset_request_" + (requests.size() + 1)));
            request.put("type", type);
            request.put("query", truncate(defaultString(firstNonEmpty(item.get("query"), item.get("reason"), item.get("description")), "supporting visual"), 220));
            request.put("duration", roundSeconds(Math.max(0.25, doubleValue(item.get("duration"), 5.0))));
            request.put("usage", defaultString(item.get("usage"), usageForAssetType(type)));
            request.put("reason", truncate(defaultString(firstNonEmpty(item.get("reason"), item.get("description"), item.get("query")), "support the edit with a relevant visual"), 220));
            requests.add(request);
            if (requests.size() >= 12) {
                break;
            }
        }
        return requests;
    }

    private List<Map<String, Object>> uiProjectAssets(
            CreatorShortVideo video,
            Object explicit,
            List<Map<String, Object>> tracks,
            List<Map<String, Object>> assetRequests
    ) {
        Map<String, Map<String, Object>> assets = new LinkedHashMap<>();
        Map<String, Object> master = new LinkedHashMap<>();
        master.put("assetId", "master_video");
        master.put("type", "master_video");
        master.put("source", "master_video");
        master.put("sourceAssetId", video == null || video.getSourceAssetId() == null ? "" : video.getSourceAssetId().toString());
        master.put("replaceable", false);
        assets.put("master_video", master);

        for (Map<String, Object> item : listOfMaps(explicit)) {
            Map<String, Object> asset = uiProjectAsset(item, "asset_" + (assets.size() + 1));
            assets.put(defaultString(asset.get("assetId"), "asset_" + assets.size()), asset);
        }
        for (Map<String, Object> track : tracks == null ? List.<Map<String, Object>>of() : tracks) {
            for (Map<String, Object> clip : listOfMaps(track.get("clips"))) {
                for (Map<String, Object> item : listOfMaps(clip.get("assets"))) {
                    Map<String, Object> asset = uiProjectAsset(item, "asset_" + (assets.size() + 1));
                    assets.put(defaultString(asset.get("assetId"), "asset_" + assets.size()), asset);
                }
            }
        }
        for (Map<String, Object> request : assetRequests == null ? List.<Map<String, Object>>of() : assetRequests) {
            String id = "requested_" + defaultString(request.get("requestId"), "asset_" + assets.size());
            if (assets.containsKey(id)) {
                continue;
            }
            Map<String, Object> asset = new LinkedHashMap<>();
            asset.put("assetId", id);
            asset.put("type", normalizeAssetType(request.get("type")));
            asset.put("source", "asset_request");
            asset.put("requestId", request.get("requestId"));
            asset.put("status", "REQUESTED");
            asset.put("replaceable", true);
            assets.put(id, asset);
        }
        return new ArrayList<>(assets.values());
    }

    private Map<String, Object> uiProjectAsset(Map<String, Object> item, String fallbackId) {
        Map<String, Object> asset = item == null ? new LinkedHashMap<>() : new LinkedHashMap<>(item);
        asset.put("assetId", defaultString(firstNonEmpty(asset.get("assetId"), asset.get("id")), fallbackId));
        asset.put("type", normalizeAssetType(firstNonEmpty(asset.get("type"), asset.get("assetType"))));
        asset.put("source", defaultString(asset.get("source"), "supporting_asset"));
        asset.put("replaceable", booleanValue(asset.get("replaceable"), true));
        if (!asset.containsKey("status")) {
            asset.put("status", asset.containsKey("url") || asset.containsKey("objectKey") ? "AVAILABLE" : "REQUESTED");
        }
        return asset;
    }

    private List<Map<String, Object>> uiProjectTimelineVersions(Object explicit, String selectedStyle) {
        List<Map<String, Object>> versions = new ArrayList<>();
        for (Map<String, Object> item : listOfMaps(explicit)) {
            Map<String, Object> version = new LinkedHashMap<>();
            version.put("version", intValue(item.get("version"), versions.size() + 1));
            version.put("createdBy", defaultString(item.get("createdBy"), "AI"));
            version.put("description", defaultString(item.get("description"), "Initial " + selectedStyle + " Edit"));
            if (item.containsKey("createdAt")) {
                version.put("createdAt", item.get("createdAt"));
            }
            versions.add(version);
            if (versions.size() >= 20) {
                break;
            }
        }
        if (versions.isEmpty()) {
            Map<String, Object> version = new LinkedHashMap<>();
            version.put("version", 1);
            version.put("createdBy", "AI");
            version.put("description", "Initial " + selectedStyle + " Edit");
            version.put("createdAt", OffsetDateTime.now().toString());
            versions.add(version);
        }
        return versions;
    }

    private List<Map<String, Object>> uiClipAssets(Object explicit, String clipId, String layout) {
        List<Map<String, Object>> assets = new ArrayList<>();
        for (Map<String, Object> item : listOfMaps(explicit)) {
            Map<String, Object> asset = uiProjectAsset(item, "asset_" + clipId + "_" + (assets.size() + 1));
            assets.add(asset);
            if (assets.size() >= 4) {
                break;
            }
        }
        if (assets.isEmpty()) {
            String assetType = assetTypeForUiLayout(layout);
            if (!assetType.isBlank()) {
                Map<String, Object> asset = new LinkedHashMap<>();
                asset.put("assetId", "asset_" + clipId + "_1");
                asset.put("type", assetType);
                asset.put("source", "asset_request");
                asset.put("status", "REQUESTED");
                asset.put("replaceable", true);
                assets.add(asset);
            }
        }
        return assets;
    }

    private void ensureDoubleScreenPanes(Map<String, Object> clip) {
        String layout = defaultString(clip.get("layout"), "");
        if ("DOUBLE_SCREEN_VERTICAL".equals(layout)) {
            clip.putIfAbsent("topPane", masterVideoPane(clip, "speaker"));
            clip.putIfAbsent("bottomPane", supportingPane(clip));
        } else if ("DOUBLE_SCREEN_HORIZONTAL".equals(layout)) {
            clip.putIfAbsent("leftPane", masterVideoPane(clip, "speaker"));
            clip.putIfAbsent("rightPane", supportingPane(clip));
        }
    }

    private void copyUiPaneIfPresent(Map<String, Object> source, Map<String, Object> target, String key) {
        Map<String, Object> pane = mapValue(source.get(key));
        if (!pane.isEmpty()) {
            target.put(key, pane);
        }
    }

    private Map<String, Object> masterVideoPane(Map<String, Object> clip, String role) {
        Map<String, Object> pane = new LinkedHashMap<>();
        pane.put("source", "master_video");
        pane.put("clipStart", clip.get("start"));
        pane.put("clipEnd", clip.get("end"));
        pane.put("role", role);
        return pane;
    }

    private Map<String, Object> supportingPane(Map<String, Object> clip) {
        List<Map<String, Object>> assets = listOfMaps(clip.get("assets"));
        if (!assets.isEmpty()) {
            Map<String, Object> asset = assets.get(0);
            Map<String, Object> pane = new LinkedHashMap<>();
            pane.put("source", "supporting_asset");
            pane.put("assetId", defaultString(asset.get("assetId"), ""));
            pane.put("role", "supporting_visual");
            return pane;
        }
        Map<String, Object> pane = new LinkedHashMap<>();
        pane.put("source", "master_video");
        pane.put("clipStart", clip.get("start"));
        pane.put("clipEnd", clip.get("end"));
        pane.put("role", "supporting_visual");
        return pane;
    }

    private double uiProjectTimelineDuration(List<Map<String, Object>> clips) {
        double duration = 0.0;
        for (Map<String, Object> clip : clips == null ? List.<Map<String, Object>>of() : clips) {
            duration = Math.max(duration, doubleValue(clip.get("end"), 0.0));
        }
        return duration;
    }

    private String uiProjectTrackTypeForAsset(Object value) {
        return switch (normalizeAssetType(value)) {
            case "screenshot" -> "SCREENSHOTS";
            case "document" -> "DOCUMENTS";
            case "map" -> "MAPS";
            case "chart" -> "CHARTS";
            default -> "SUPPORTING_VIDEO";
        };
    }

    private String usageForAssetType(String type) {
        return switch (normalizeAssetType(type)) {
            case "screenshot", "document" -> "evidence_view";
            case "map" -> "map_view";
            case "chart" -> "stat_card";
            default -> "double_screen";
        };
    }

    private String layoutForAssetType(Object type) {
        return switch (normalizeAssetType(type)) {
            case "screenshot", "document" -> "SCREENSHOT_VIEW";
            case "map" -> "MAP_VIEW";
            case "chart" -> "STAT_CARD";
            default -> "FULL_FOOTAGE";
        };
    }

    private String assetTypeForUiLayout(String layout) {
        return switch (normalizeUiProjectLayout(layout, "9:16")) {
            case "SCREENSHOT_VIEW" -> "screenshot";
            case "MAP_VIEW" -> "map";
            case "STAT_CARD" -> "chart";
            case "EVIDENCE_VIEW" -> "document";
            default -> "";
        };
    }

    private List<Map<String, Object>> sanitizeWindowReasonList(Object explicit, String fallbackReason) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> item : listOfMaps(explicit)) {
            double start = Math.max(0.0, doubleValue(item.get("start"), result.size() * 3.0));
            double end = Math.max(start + 0.25, doubleValue(item.get("end"), start + 2.0));
            result.add(windowReason(start, end, defaultString(item.get("reason"), fallbackReason)));
            if (result.size() >= 8) {
                break;
            }
        }
        return result;
    }

    private List<Map<String, Object>> sanitizeAssetOpportunities(Object explicit) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> item : listOfMaps(explicit)) {
            double start = Math.max(0.0, doubleValue(item.get("start"), result.size() * 3.0));
            double end = Math.max(start + 0.25, doubleValue(item.get("end"), start + 2.0));
            result.add(assetOpportunity(
                    start,
                    end,
                    defaultString(firstNonEmpty(item.get("assetType"), item.get("type")), "supporting_video"),
                    defaultString(firstNonEmpty(item.get("description"), item.get("reason")), "support the edit with a relevant visual")
            ));
            if (result.size() >= 8) {
                break;
            }
        }
        return result;
    }

    private Map<String, Object> styleSuggestion(String style, double confidence) {
        Map<String, Object> suggestion = new LinkedHashMap<>();
        suggestion.put("style", normalizeTimelineStyle(style));
        suggestion.put("confidence", roundSeconds(Math.max(0.0, Math.min(1.0, confidence))));
        return suggestion;
    }

    private Map<String, Object> criticalScene(String sceneId, double start, double end, String sceneType, double importance, String reason) {
        Map<String, Object> scene = new LinkedHashMap<>();
        scene.put("sceneId", defaultString(sceneId, "scene"));
        scene.put("start", roundSeconds(Math.max(0.0, start)));
        scene.put("end", roundSeconds(Math.max(start + 0.25, end)));
        scene.put("sceneType", normalizeCriticalSceneType(sceneType));
        scene.put("importance", roundSeconds(Math.max(0.0, Math.min(1.0, importance))));
        scene.put("reason", truncate(defaultString(reason, "important moment"), 180));
        return scene;
    }

    private Map<String, Object> windowReason(double start, double end, String reason) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("start", roundSeconds(Math.max(0.0, start)));
        item.put("end", roundSeconds(Math.max(start + 0.25, end)));
        item.put("reason", truncate(defaultString(reason, "retention opportunity"), 180));
        return item;
    }

    private Map<String, Object> assetOpportunity(double start, double end, String assetType, String description) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("start", roundSeconds(Math.max(0.0, start)));
        item.put("end", roundSeconds(Math.max(start + 0.25, end)));
        item.put("assetType", normalizeAssetType(assetType));
        item.put("description", truncate(defaultString(description, "support the edit with a relevant visual"), 220));
        return item;
    }

    private List<Map<String, Object>> compactCriticalScenes(List<Map<String, Object>> scenes) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> scene : scenes == null ? List.<Map<String, Object>>of() : scenes) {
            if (doubleValue(scene.get("end"), 0.0) <= doubleValue(scene.get("start"), 0.0)) {
                continue;
            }
            result.add(scene);
            if (result.size() >= 10) {
                break;
            }
        }
        return result;
    }

    private double segmentDuration(Map<String, Object> segment, double fallback) {
        double explicit = doubleValue(segment.get("durationSeconds"), 0.0);
        if (explicit > 0.0) {
            return explicit;
        }
        double sourceStart = doubleValue(firstNonEmpty(segment.get("sourceStart"), segment.get("start")), 0.0);
        double sourceEnd = doubleValue(firstNonEmpty(segment.get("sourceEnd"), segment.get("end")), sourceStart + fallback);
        return Math.max(0.25, sourceEnd - sourceStart);
    }

    private String criticalSceneTypeFor(Map<String, Object> segment, Map<String, Object> payload, int index, int count) {
        if (index == 0) {
            return "HOOK";
        }
        if (index == count - 1) {
            return "CONCLUSION";
        }
        String text = (defaultString(segment.get("reason"), "") + " "
                + defaultString(segment.get("operation"), "") + " "
                + defaultString(segment.get("transcript"), "") + " "
                + defaultString(payload.get("hookType"), "")).toLowerCase(Locale.ROOT);
        if (text.contains("stat") || text.matches(".*\\b\\d+(?:\\.\\d+)?%?.*")) {
            return "STATISTIC";
        }
        if (text.contains("evidence") || text.contains("proof") || text.contains("source")) {
            return text.contains("proof") ? "PROOF" : "EVIDENCE";
        }
        if (text.contains("reveal") || text.contains("surprise")) {
            return "REVEAL";
        }
        if (text.contains("quote") || text.contains("said")) {
            return "QUOTE";
        }
        if (text.contains("reaction")) {
            return "REACTION";
        }
        return "CLAIM";
    }

    private double criticalImportance(String sceneType, int index, int count) {
        return switch (normalizeCriticalSceneType(sceneType)) {
            case "HOOK" -> 0.95;
            case "REVEAL", "EVIDENCE", "PROOF", "STATISTIC" -> 0.88;
            case "CONCLUSION" -> 0.78;
            default -> Math.max(0.65, 0.82 - (index * 0.03));
        };
    }

    private String criticalReason(Map<String, Object> segment, String sceneType) {
        String reason = defaultString(firstNonEmpty(segment.get("reason"), segment.get("operation"), segment.get("summary")), "");
        if (!reason.isBlank()) {
            return reason;
        }
        return switch (normalizeCriticalSceneType(sceneType)) {
            case "HOOK" -> "opening hook";
            case "CONCLUSION" -> "closing takeaway";
            case "EVIDENCE", "PROOF" -> "supporting evidence moment";
            case "STATISTIC" -> "important numerical claim";
            case "REVEAL" -> "reveal moment";
            default -> "main story point";
        };
    }

    private String normalizeCriticalSceneType(Object value) {
        String normalized = editorialToken(value);
        return allowedCriticalSceneTypes().contains(normalized) ? normalized : "CLAIM";
    }

    private String normalizeTimelineStyle(Object value) {
        String normalized = editorialToken(value);
        return allowedTimelineStyles().contains(normalized) ? normalized : "";
    }

    private String normalizeTimelineLayout(Object value) {
        String normalized = editorialToken(value);
        if (allowedTimelineLayouts().contains(normalized)) {
            return normalized;
        }
        return switch (normalized) {
            case "TALKING_HEAD", "SPEAKER", "SPEAKER_ONLY" -> "FULL_SCREEN_SPEAKER";
            case "SPEAKER_BROLL", "SPLIT_SCREEN", "DOUBLE", "DOUBLE_SCREEN_VERTICAL", "DOUBLE_SCREEN_HORIZONTAL" -> "DOUBLE_SCREEN";
            case "EVIDENCE", "PROOF" -> "EVIDENCE_VIEW";
            case "HEADLINE" -> "HEADLINE_CARD";
            case "STAT", "STAT_CALLOUT" -> "STAT_CARD";
            case "CONCLUSION" -> "CONCLUSION_CARD";
            default -> "FULL_SCREEN_SPEAKER";
        };
    }

    private String normalizeUiProjectLayout(Object value, String aspectRatio) {
        String normalized = editorialToken(value);
        if ("DOUBLE_SCREEN".equals(normalized) || "SPLIT_SCREEN".equals(normalized) || "DOUBLE".equals(normalized)) {
            return "9:16".equals(defaultString(aspectRatio, "9:16")) ? "DOUBLE_SCREEN_VERTICAL" : "DOUBLE_SCREEN_HORIZONTAL";
        }
        if (allowedUiProjectLayouts().contains(normalized)) {
            return normalized;
        }
        return switch (normalized) {
            case "TALKING_HEAD", "SPEAKER", "SPEAKER_ONLY" -> "FULL_SCREEN_SPEAKER";
            case "EVIDENCE", "PROOF" -> "EVIDENCE_VIEW";
            case "HEADLINE" -> "HEADLINE_CARD";
            case "STAT", "STAT_CALLOUT" -> "STAT_CARD";
            case "CONCLUSION" -> "CONCLUSION_CARD";
            default -> "FULL_SCREEN_SPEAKER";
        };
    }

    private String normalizeUiProjectTrackType(Object value) {
        String normalized = editorialToken(value);
        return allowedUiProjectTrackTypes().contains(normalized) ? normalized : "PRIMARY_VIDEO";
    }

    private String normalizeTimelinePurpose(Object value) {
        String normalized = editorialToken(value);
        return allowedTimelinePurposes().contains(normalized) ? normalized : "CLAIM";
    }

    private String normalizeTimelineEffect(Object value) {
        String normalized = editorialToken(value);
        return allowedTimelineEffects().contains(normalized) ? normalized : "";
    }

    private String normalizeAssetType(Object value) {
        String normalized = defaultString(value, "supporting_video").trim().toLowerCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        return switch (normalized) {
            case "supporting_video", "screenshot", "chart", "map", "document", "actual_footage", "broll", "headline" -> normalized;
            default -> "supporting_video";
        };
    }

    private String editorialToken(Object value) {
        return defaultString(value, "").trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    }

    private Set<String> allowedEditorialVideoTypes() {
        return Set.of("JOURNALISM", "DOCUMENTARY", "PODCAST_QA", "FINANCE_EXPLAINER", "EDUCATIONAL", "STORYTELLING", "VIRAL_SHORTS", "INTERVIEW", "COMMENTARY");
    }

    private Set<String> allowedCriticalSceneTypes() {
        return Set.of("HOOK", "CLAIM", "REVEAL", "EVIDENCE", "REACTION", "STATISTIC", "QUOTE", "PROOF", "CONCLUSION");
    }

    private Set<String> allowedTimelineStyles() {
        return Set.of("AUTO", "JOURNALISM_DOUBLE_SCREEN", "DOCUMENTARY", "PODCAST_QA", "FINANCE_EXPLAINER", "VIRAL_SHORTS", "EDUCATIONAL", "STORYTELLING");
    }

    private Set<String> allowedTimelineLayouts() {
        return Set.of("FULL_SCREEN_SPEAKER", "DOUBLE_SCREEN", "FULL_FOOTAGE", "PICTURE_IN_PICTURE", "EVIDENCE_VIEW", "SCREENSHOT_VIEW", "MAP_VIEW", "HEADLINE_CARD", "STAT_CARD", "CONCLUSION_CARD");
    }

    private Set<String> allowedUiProjectLayouts() {
        return Set.of("FULL_SCREEN_SPEAKER", "DOUBLE_SCREEN_VERTICAL", "DOUBLE_SCREEN_HORIZONTAL", "FULL_FOOTAGE", "PICTURE_IN_PICTURE", "HEADLINE_CARD", "STAT_CARD", "EVIDENCE_VIEW", "SCREENSHOT_VIEW", "MAP_VIEW", "CONCLUSION_CARD");
    }

    private Set<String> allowedUiProjectTrackTypes() {
        return Set.of("PRIMARY_VIDEO", "SUPPORTING_VIDEO", "SCREENSHOTS", "DOCUMENTS", "MAPS", "CHARTS", "EFFECTS", "CAPTIONS", "AUDIO", "OVERLAYS");
    }

    private Set<String> allowedTimelinePurposes() {
        return Set.of("HOOK", "CLAIM", "REVEAL", "EVIDENCE", "REACTION", "STATISTIC", "QUOTE", "PROOF", "CONCLUSION", "EXPLANATION");
    }

    private Set<String> allowedTimelineEffects() {
        return Set.of("LIGHTNING_FLASH", "WHITE_FLASH", "HEADLINE_BLAST", "PUNCH_ZOOM", "SPEED_RAMP", "CAMERA_SHAKE", "FREEZE_FRAME", "HIGHLIGHT_PULSE", "GLITCH_TRANSITION", "SWIPE_TRANSITION", "BLUR_TRANSITION", "COUNTDOWN_EFFECT", "STAT_POP", "QUOTE_POP");
    }

    private CreatorShortVideo loadVideo(UUID videoId, String tenantId, String userId) {
        if (videoId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "short video id is required.");
        }
        return videoRepository.findByIdAndTenantIdAndUserId(videoId, safeTenantId(tenantId), safeUserId(userId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Short video was not found."));
    }

    private ShortGenerationResponse toResponse(CreatorShortVideo video) {
        List<ShortCandidateResponse> candidates = candidateRepository.findByVideoIdOrderByRankIndexAsc(video.getId())
                .stream()
                .map(this::toCandidateResponse)
                .toList();
        Map<String, Object> sourceAsset = video.getSourceAssetId() == null
                ? new LinkedHashMap<>()
                : assetRepository.findById(video.getSourceAssetId()).map(this::sourceAssetMap).orElseGet(LinkedHashMap::new);
        return new ShortGenerationResponse(
                video.getId(),
                video.getGenerationJobId(),
                video.getSourceAssetId(),
                video.getStatus(),
                video.getTitle(),
                video.getPlatform(),
                video.getTargetDurationSeconds(),
                video.getRequestedShorts(),
                video.getReviewMode(),
                sourceAsset,
                mutableMap(video.getSettings()),
                mutableMap(video.getVideoDna()),
                listOfMaps(mutableMap(video.getTranscriptPayload()).get("nodes")),
                mutableMap(video.getGraphPayload()),
                listOfMaps(mutableMap(video.getTracePayload()).get("stages")),
                candidates,
                mutableMap(video.getMetadata()),
                video.getCreatedAt(),
                video.getUpdatedAt(),
                video.getCompletedAt()
        );
    }

    private ShortCandidateResponse toCandidateResponse(CreatorShortCandidate candidate) {
        return new ShortCandidateResponse(
                candidate.getId(),
                candidate.getVideoId(),
                candidate.getAssetId(),
                candidate.getRankIndex(),
                candidate.getTitle(),
                candidate.getDurationSeconds(),
                candidate.getScore(),
                candidate.getHookType(),
                candidate.getStatus(),
                candidate.getReviewStatus(),
                mutableMap(candidate.getEditDecisionList()),
                mutableMap(candidate.getCaptionPlan()),
                mutableMap(candidate.getRenderManifest()),
                mutableMap(candidate.getMetadata()),
                candidate.getCreatedAt(),
                candidate.getUpdatedAt()
        );
    }

    private Map<String, Object> buildSettings(GenerateShortsRequest request) {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("platform", normalizePlatform(request.platform()));
        settings.put("targetDurationSeconds", normalizeTargetDuration(request.targetDurationSeconds()));
        settings.put("requestedShorts", normalizeRequestedShorts(request.requestedShorts()));
        settings.put("reviewMode", normalizeReviewMode(request.reviewMode()));
        settings.put("creatorProfile", parseJsonObject(request.creatorProfileJson()));
        settings.put("notes", defaultString(request.notes(), ""));
        settings.put("executionMode", normalizeExecutionMode(request.executionMode()));
        settings.put("manualStepMode", "MANUAL_STEP".equals(normalizeExecutionMode(request.executionMode())));
        settings.put("graphReuseEnabled", true);
        settings.put("humanReviewEnabled", !"AUTO".equals(normalizeReviewMode(request.reviewMode())));
        return settings;
    }

    private Map<String, Object> initialVideoMetadata(
            CreatorAsset sourceAsset,
            AssetStorageService.StoredObject stored,
            GenerateShortsRequest request,
            String sourceLabel,
            Map<String, Object> ingestionMetadata
    ) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("sourceBucket", stored.bucket());
        metadata.put("sourceObjectKey", stored.objectKey());
        metadata.put("sourceAssetId", sourceAsset.getId().toString());
        metadata.put("sourceAssetType", ASSET_TYPE_SOURCE_VIDEO);
        metadata.put("sourceSignedUrl", stored.signedUrl());
        metadata.put("requestedAt", OffsetDateTime.now().toString());
        metadata.put("notes", defaultString(request.notes(), ""));
        metadata.put("executionMode", normalizeExecutionMode(request.executionMode()));
        metadata.put("agentContractVersion", 1);
        metadata.put("source", sourceLabel);
        if (ingestionMetadata != null && !ingestionMetadata.isEmpty()) {
            metadata.put("ingestion", new LinkedHashMap<>(ingestionMetadata));
        }
        return metadata;
    }

    private GenerateShortsRequest safeGenerateRequest(GenerateShortsRequest request) {
        return request == null
                ? new GenerateShortsRequest(null, null, null, null, null, null, null, null, null)
                : request;
    }

    private void assertWalletBalance(GenerateShortsRequest request, String tenantId, String userId) {
        creatorAiService.assertWalletBalanceForModelRun(
                JOB_TYPE,
                new CreatorAiService.AiUsageContext(tenantId, userId, request.projectId(), null, null)
        );
    }
    private void validateUpload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Upload a long source video before generating shorts.");
        }
        validateVideoMetadata(file.getOriginalFilename(), file.getContentType());
    }

    private void validateVideoMetadata(String originalFilename, String contentTypeValue) {
        String contentType = defaultString(contentTypeValue, "application/octet-stream").toLowerCase(Locale.ROOT);
        String filename = defaultString(originalFilename, "");
        boolean videoType = contentType.startsWith("video/");
        boolean octet = "application/octet-stream".equals(contentType);
        boolean videoExtension = VIDEO_EXTENSIONS.contains(extension(filename));
        if (!videoType && !(octet && videoExtension)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Generate Shorts expects a video upload.");
        }
    }

    private void validateUploadId(UUID uploadId) {
        if (uploadId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Upload id is required.");
        }
    }

    private void validateChunkIndex(int chunkIndex, int totalChunks) {
        if (chunkIndex < 0 || chunkIndex >= totalChunks) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Chunk index is outside the upload range.");
        }
    }

    private void validateDirectPartNumber(int partNumber, int totalParts) {
        if (partNumber < 1 || partNumber > totalParts) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Multipart part number is outside the upload range.");
        }
    }

    private String validateStorageUploadId(String storageUploadId) {
        String safe = defaultString(storageUploadId, "").trim();
        if (safe.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Storage upload id is required.");
        }
        return safe;
    }

    private long normalizeDirectUploadSize(Long sizeBytes) {
        long size = sizeBytes == null ? 0L : sizeBytes;
        if (size <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Upload size is required for direct multipart upload.");
        }
        if (size > MAX_DIRECT_UPLOAD_SIZE_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Direct video upload supports files up to 10 GB.");
        }
        return size;
    }

    private long normalizeDirectUploadPartSize(Long partSizeBytes) {
        long partSize = partSizeBytes == null || partSizeBytes <= 0
                ? DEFAULT_DIRECT_UPLOAD_PART_SIZE_BYTES
                : partSizeBytes;
        if (partSize < MIN_DIRECT_UPLOAD_PART_SIZE_BYTES) {
            partSize = MIN_DIRECT_UPLOAD_PART_SIZE_BYTES;
        }
        if (partSize > MAX_CHUNK_SIZE_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Direct upload part size must be 64 MB or less.");
        }
        return partSize;
    }

    private int normalizeDirectUploadPartCount(Integer requestedTotalParts, long sizeBytes, long partSizeBytes) {
        int computedParts = (int) Math.ceil(sizeBytes / (double) Math.max(1L, partSizeBytes));
        if (computedParts <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Direct upload part count could not be calculated.");
        }
        if (requestedTotalParts != null && requestedTotalParts > 0 && requestedTotalParts != computedParts) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Direct upload part count does not match file size and part size.");
        }
        if (computedParts > MAX_CHUNK_COUNT) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Too many upload parts.");
        }
        return computedParts;
    }

    private long normalizeChunkSize(Long chunkSizeBytes) {
        long chunkSize = chunkSizeBytes == null || chunkSizeBytes <= 0 ? DEFAULT_CHUNK_SIZE_BYTES : chunkSizeBytes;
        if (chunkSize > MAX_CHUNK_SIZE_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Chunk size must be 64 MB or less.");
        }
        return chunkSize;
    }

    private int normalizeTotalChunks(Integer totalChunks, Long sizeBytes, long chunkSizeBytes) {
        int count = totalChunks == null || totalChunks <= 0
                ? (sizeBytes == null || sizeBytes <= 0 ? 0 : (int) Math.ceil(sizeBytes / (double) Math.max(1L, chunkSizeBytes)))
                : totalChunks;
        if (count <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Total chunk count is required.");
        }
        if (count > MAX_CHUNK_COUNT) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Too many upload chunks.");
        }
        return count;
    }

    private String sourceObjectKey(String tenantId, UUID videoId, String originalFilename) {
        return "shorts/%s/%s/source/%s-%s".formatted(
                storageSegment(tenantId),
                DateTimeFormatter.BASIC_ISO_DATE.format(java.time.LocalDate.now()),
                videoId,
                sanitizeFilename(originalFilename)
        );
    }

    private String chunkObjectKey(String tenantId, String userId, UUID uploadId, int chunkIndex) {
        return "short-uploads/%s/%s/%s/chunks/%05d.part".formatted(
                storageSegment(tenantId),
                storageSegment(userId),
                uploadId,
                chunkIndex
        );
    }

    private String directUploadObjectKey(String tenantId, String userId, UUID uploadId, UUID videoId, String originalFilename) {
        return "shorts/%s/direct-uploads/%s/%s/source/%s-%s".formatted(
                storageSegment(tenantId),
                storageSegment(userId),
                uploadId,
                videoId,
                sanitizeFilename(originalFilename)
        );
    }

    private String storageSegment(String value) {
        String safe = defaultString(value, "unknown")
                .replaceAll("[^A-Za-z0-9._-]", "-")
                .replaceAll("-+", "-");
        if (safe.length() > 96) {
            return safe.substring(0, 96);
        }
        return safe;
    }

    private List<Map<String, Object>> fallbackTranscript(CreatorShortVideo video) {
        List<Map<String, Object>> nodes = new ArrayList<>();
        nodes.add(videoNode("n-001", 0, 12, "Speaker 1", "Opening context from " + video.getTitle(), "scene-001", 62, 30, 72, List.of("F01", "F02")));
        nodes.add(videoNode("n-002", 12, 42, "Speaker 1", "Best answer or core explanation for the short candidate.", "scene-001", 78, 38, 88, List.of("F03", "F04")));
        nodes.add(videoNode("n-003", 42, 60, "Speaker 1", "Takeaway and payoff for the audience.", "scene-002", 70, 34, 82, List.of("F05", "F06")));
        return nodes;
    }

    private Map<String, Object> fallbackVideoDna(CreatorShortVideo video) {
        Map<String, Object> dna = new LinkedHashMap<>();
        dna.put("primaryType", "unknown");
        dna.put("confidence", 0.42);
        dna.put("structureType", "storytelling");
        dna.put("analysisSource", "upload_metadata");
        dna.put("reason", "No transcript worker output was attached, so the planner generated a provisional graph from upload metadata.");
        dna.put("platform", video.getPlatform());
        return dna;
    }

    private Map<String, Object> fallbackGraph(List<Map<String, Object>> transcript, Map<String, Object> videoDna) {
        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("graphViews", List.of("Conversation Graph", "Story Graph", "Scene Graph", "Compression Graph"));
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (Map<String, Object> transcriptNode : transcript) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", transcriptNode.get("id"));
            node.put("label", truncate(defaultString(transcriptNode.get("transcript"), "Segment"), 64));
            node.put("type", "SEGMENT");
            node.put("start", transcriptNode.get("start"));
            node.put("end", transcriptNode.get("end"));
            nodes.add(node);
        }
        List<Map<String, Object>> edges = new ArrayList<>();
        for (int index = 1; index < transcript.size(); index++) {
            edges.add(edge(
                    stringValue(transcript.get(index - 1).get("id")),
                    stringValue(transcript.get(index).get("id")),
                    edgeTypeFor(videoDna),
                    "Preserve semantic continuity for compression."
            ));
        }
        graph.put("nodes", nodes);
        graph.put("edges", edges);
        graph.put("analysisSource", videoDna.getOrDefault("analysisSource", "upload_metadata"));
        return graph;
    }

    private List<Map<String, Object>> fallbackCandidates(CreatorShortVideo video, List<Map<String, Object>> transcript, Map<String, Object> graph) {
        int count = Math.max(1, Math.min(video.getRequestedShorts() == null ? 20 : video.getRequestedShorts(), 50));
        List<Map<String, Object>> candidates = new ArrayList<>();
        List<String> hooks = List.of("Question Hook", "Contrarian Hook", "Aha Moment", "Problem/Solution", "Reveal Hook");
        for (int index = 0; index < count; index++) {
            int rank = index + 1;
            String hook = hooks.get(index % hooks.size());
            Map<String, Object> candidate = new LinkedHashMap<>();
            candidate.put("title", rank == 1 ? truncate(video.getTitle(), 180) : truncate(video.getTitle() + " - angle " + rank, 180));
            candidate.put("durationSeconds", video.getTargetDurationSeconds());
            candidate.put("score", Math.max(70, 96 - (rank * 2)));
            candidate.put("hookType", hook);
            candidate.put("editDecisionList", fallbackEditDecisionList(rank));
            candidate.put("captionPlan", fallbackCaptionPlan(video));
            candidate.put("renderManifest", fallbackRenderManifest(video));
            candidate.put("metadata", Map.of(
                    "chain", chainForHook(hook),
                    "graphNodeCount", listOfMaps(graph.get("nodes")).size(),
                    "transcriptNodeCount", transcript.size(),
                    "selectionReason", "Provisional candidate generated from the reusable graph contract."
            ));
            candidates.add(candidate);
        }
        return candidates;
    }

    private Map<String, Object> fallbackEditDecisionList(int rank) {
        Map<String, Object> edl = new LinkedHashMap<>();
        edl.put("strategy", rank % 3 == 0 ? "KEEP_PROBLEM_SOLUTION_CHAIN" : "KEEP_QA_CHAIN");
        edl.put("segments", List.of(
                Map.of("nodeId", "n-001", "operation", "KEEP", "reason", "Context for hook."),
                Map.of("nodeId", "n-002", "operation", rank % 3 == 0 ? "KEEP_PROBLEM_SOLUTION_CHAIN" : "KEEP_QA_CHAIN", "reason", "Core insight."),
                Map.of("nodeId", "n-003", "operation", "KEEP", "reason", "Payoff.")
        ));
        edl.put("version", 1);
        return edl;
    }

    private Map<String, Object> fallbackCaptionPlan(CreatorShortVideo video) {
        Map<String, Object> captions = new LinkedHashMap<>();
        captions.put("style", "platform-aware");
        captions.put("platform", video.getPlatform());
        captions.put("density", "medium");
        captions.put("source", "fallback_waiting_for_final_edl");
        captions.put("captionRepairMode", "NEEDS_FINAL_EDL");
        captions.put("requiresFinalEdlCaptionRepair", true);
        captions.put("captions", List.of());
        return captions;
    }

    private Map<String, Object> fallbackRenderManifest(CreatorShortVideo video) {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("renderStatus", "PENDING_REVIEW");
        manifest.put("aspectRatio", aspectRatioFor(video.getPlatform()));
        manifest.put("targetDurationSeconds", video.getTargetDurationSeconds());
        manifest.put("safeZones", safeZonesFor(video.getPlatform()));
        manifest.put("requiresHumanReview", "REVIEW".equalsIgnoreCase(video.getReviewMode()));
        return manifest;
    }

    private List<Map<String, Object>> withRetryAttempt(List<Map<String, Object>> rows, int attempt) {
        List<Map<String, Object>> updated = new ArrayList<>();
        for (Map<String, Object> row : rows == null ? List.<Map<String, Object>>of() : rows) {
            Map<String, Object> copy = new LinkedHashMap<>(row);
            Map<String, Object> metadata = mapValue(copy.get("metadata"));
            metadata.put("criticRetryAttempt", attempt);
            copy.put("criticRetryAttempt", attempt);
            copy.put("metadata", metadata);
            updated.add(copy);
        }
        return updated;
    }

    private boolean needsAnotherRepairPass(List<Map<String, Object>> candidates) {
        for (Map<String, Object> candidate : candidates == null ? List.<Map<String, Object>>of() : candidates) {
            Map<String, Object> metadata = mapValue(candidate.get("metadata"));
            Map<String, Object> critics = mapValue(metadata.get("critics"));
            if (criticFailed(critics.get("compressionCritic"))
                    || criticFailed(critics.get("hookCritic"))
                    || criticFailed(critics.get("captionCritic"))
                    || criticFailed(critics.get("globalCritic"))) {
                return true;
            }
        }
        return false;
    }

    private boolean criticFailed(Object value) {
        Map<String, Object> critic = mapValue(value);
        if (critic.isEmpty()) {
            return false;
        }
        String status = defaultString(critic.get("status"), "");
        return "FAIL".equalsIgnoreCase(status) || Boolean.FALSE.equals(critic.get("passed"));
    }

    private Map<String, Object> withShortCandidateGraphNodes(Map<String, Object> graph, List<CreatorShortCandidate> candidates) {
        Map<String, Object> result = mutableMap(graph);
        List<Map<String, Object>> nodes = listOfMaps(result.get("nodes"));
        List<Map<String, Object>> edges = listOfMaps(result.get("edges"));
        nodes.removeIf(node -> "SHORT_CANDIDATE".equalsIgnoreCase(defaultString(node.get("type"), "")));
        edges.removeIf(edge -> "SHORT_CANDIDATE_GRAPH_EDGE".equalsIgnoreCase(defaultString(edge.get("source"), "")));

        List<Map<String, Object>> shortNodes = new ArrayList<>();
        java.util.Set<String> edgeKeys = new java.util.HashSet<>();
        for (CreatorShortCandidate candidate : candidates == null ? List.<CreatorShortCandidate>of() : candidates) {
            if (candidate == null || candidate.getId() == null) {
                continue;
            }
            String shortNodeId = "short-" + candidate.getId();
            Map<String, Object> metadata = mutableMap(candidate.getMetadata());
            Map<String, Object> edl = mutableMap(candidate.getEditDecisionList());
            Map<String, Object> storyIntent = firstNonEmptyMap(metadata.get("storyIntent"), mapValue(metadata.get("compressionPlanMetadata")).get("storyIntent"));
            Map<String, Object> storyBeatPlan = firstNonEmptyMap(metadata.get("storyBeatPlan"), mapValue(metadata.get("compressionPlanMetadata")).get("storyBeatPlan"));
            Map<String, Object> hookPlan = mapValue(metadata.get("hookPlan"));

            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", shortNodeId);
            node.put("label", truncate(defaultString(candidate.getTitle(), "Short " + candidate.getRankIndex()), 80));
            node.put("type", "SHORT_CANDIDATE");
            node.put("rankIndex", candidate.getRankIndex());
            node.put("candidateId", candidate.getId().toString());
            node.put("assetId", candidate.getAssetId() == null ? null : candidate.getAssetId().toString());
            node.put("status", candidate.getStatus());
            node.put("reviewStatus", candidate.getReviewStatus());
            node.put("score", candidate.getScore());
            node.put("hookType", candidate.getHookType());
            node.put("storyIntent", storyIntent);
            node.put("storyBeatPlan", storyBeatPlan);
            node.put("hookPlan", hookPlan);
            node.put("editDecisionList", edl);
            node.put("timelinePreview", timelinePreviewFor(edl));
            node.put("manualTimeline", Map.of(
                    "editable", true,
                    "segmentCount", listOfMaps(edl.get("segments")).size(),
                    "durationSeconds", edl.get("actualDurationSeconds")
            ));
            node.put("sourceNodeIds", sourceNodeIds(edl));
            node.put("sceneIds", sceneIds(edl));
            node.put("ui", Map.of(
                    "blink", true,
                    "editable", true,
                    "modal", "SHORT_STORY_GRAPH_NODE_EDITOR",
                    "contextLocked", true,
                    "editableFields", List.of("intent", "beats", "hook", "timeline")
            ));
            node.put("humanLoopPolicy", Map.of(
                    "mustStayInsideOriginalStory", true,
                    "allowedSourceNodeIds", sourceNodeIds(edl),
                    "allowedSceneIds", sceneIds(edl),
                    "maxIntentChars", 220,
                    "maxHookChars", 120
            ));
            nodes.add(node);
            shortNodes.add(node);

            for (String nodeId : sourceNodeIds(edl)) {
                addShortEdge(edges, edgeKeys, nodeId, shortNodeId, "INCLUDED_IN_SHORT", "Transcript node included in generated short.");
            }
            for (String sceneId : sceneIds(edl)) {
                addShortEdge(edges, edgeKeys, sceneId, shortNodeId, "SCENE_USED_IN_SHORT", "Visual scene used by generated short.");
            }
        }
        result.put("nodes", nodes);
        result.put("edges", edges);
        result.put("shortCandidateGraph", Map.of(
                "source", "creator_short_generation_service",
                "nodeCount", shortNodes.size(),
                "humanInLoopEnabled", true,
                "contextLocked", true,
                "updatedAt", OffsetDateTime.now().toString()
        ));
        return result;
    }

    private Map<String, Object> firstNonEmptyMap(Object... values) {
        for (Object value : values == null ? new Object[0] : values) {
            Map<String, Object> map = mapValue(value);
            if (!map.isEmpty()) {
                return map;
            }
        }
        return new LinkedHashMap<>();
    }

    private List<String> sourceNodeIds(Map<String, Object> edl) {
        java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>();
        for (Map<String, Object> segment : listOfMaps(edl.get("segments"))) {
            String nodeId = defaultString(segment.get("nodeId"), "");
            if (!nodeId.isBlank()) {
                ids.add(nodeId);
            }
        }
        return new ArrayList<>(ids);
    }

    private List<String> sceneIds(Map<String, Object> edl) {
        java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>();
        ids.addAll(listOfStrings(edl.get("sceneIds")));
        for (Map<String, Object> segment : listOfMaps(edl.get("segments"))) {
            String sceneId = defaultString(segment.get("sceneId"), "");
            if (!sceneId.isBlank()) {
                ids.add(sceneId);
            }
        }
        ids.remove("");
        return new ArrayList<>(ids);
    }

    private Map<String, Object> timelinePreviewFor(Map<String, Object> editDecisionList) {
        List<Map<String, Object>> previews = new ArrayList<>();
        List<Integer> waveform = new ArrayList<>();
        int index = 0;
        for (Map<String, Object> segment : listOfMaps(editDecisionList.get("segments"))) {
            double start = doubleValue(firstNonEmpty(segment.get("sourceStart"), segment.get("start")), 0.0);
            double end = doubleValue(firstNonEmpty(segment.get("sourceEnd"), segment.get("end")), start + 0.1);
            double duration = Math.max(0.1, end - start);
            Map<String, Object> preview = new LinkedHashMap<>();
            preview.put("segmentId", defaultString(firstNonEmpty(segment.get("segmentId"), segment.get("id")), "segment-" + index));
            preview.put("nodeId", segment.get("nodeId"));
            preview.put("sceneId", segment.get("sceneId"));
            preview.put("speaker", defaultString(firstNonEmpty(segment.get("speaker"), segment.get("activeSpeaker")), ""));
            preview.put("speakerFocus", mapValue(segment.get("speakerFocus")));
            preview.put("sourceStart", roundSeconds(start));
            preview.put("sourceEnd", roundSeconds(end));
            preview.put("durationSeconds", roundSeconds(duration));
            preview.put("frames", listOfStrings(segment.get("frames")).stream().limit(4).toList());
            previews.add(preview);
            int bars = Math.max(2, Math.min(12, (int) Math.round(duration / 2.0)));
            int seed = Math.abs(defaultString(segment.get("nodeId"), "node-" + index).hashCode());
            for (int bar = 0; bar < bars; bar++) {
                waveform.add(24 + Math.abs(seed + (bar * 17)) % 68);
            }
            index++;
        }
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("source", "creator_short_generation_service");
        preview.put("segments", previews);
        preview.put("waveform", waveform);
        preview.put("durationSeconds", editDecisionList.get("actualDurationSeconds"));
        preview.put("generatedAt", OffsetDateTime.now().toString());
        return preview;
    }

    private void addShortEdge(List<Map<String, Object>> edges, java.util.Set<String> edgeKeys, String from, String to, String type, String reason) {
        if (from == null || from.isBlank() || to == null || to.isBlank()) {
            return;
        }
        String key = from + "->" + to + ":" + type;
        if (!edgeKeys.add(key)) {
            return;
        }
        Map<String, Object> edge = new LinkedHashMap<>();
        edge.put("from", from);
        edge.put("to", to);
        edge.put("type", type);
        edge.put("source", "SHORT_CANDIDATE_GRAPH_EDGE");
        edge.put("reason", reason);
        edges.add(edge);
    }
    private List<String> listOfStrings(Object value) {
        if (value instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object item : list) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    result.add(String.valueOf(item));
                }
            }
            return result;
        }
        return new ArrayList<>();
    }
    private List<Map<String, Object>> defaultTrace(List<Map<String, Object>> existingTrace, Map<String, Object> videoDna) {
        List<Map<String, Object>> trace = new ArrayList<>(existingTrace);
        Set<String> existingStages = trace.stream()
                .map(row -> stringValue(row.get("stage")).toUpperCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toSet());
        for (String stage : PIPELINE_STAGES) {
            if (existingStages.contains(stage)) {
                continue;
            }
            String status = "NOT_RUN";
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("stage", stage);
            row.put("status", status);
            row.put("summary", traceSummary(stage, videoDna));
            row.put("agent", stage.toLowerCase(Locale.ROOT).replace('_', '-'));
            row.put("timestamp", OffsetDateTime.now().toString());
            trace.add(row);
        }
        return trace;
    }

    private List<Map<String, Object>> withoutTraceStage(List<Map<String, Object>> trace, String stage) {
        List<Map<String, Object>> filtered = new ArrayList<>();
        String normalizedStage = defaultString(stage, "").toUpperCase(Locale.ROOT);
        for (Map<String, Object> row : trace == null ? List.<Map<String, Object>>of() : trace) {
            if (!normalizedStage.equals(defaultString(row.get("stage"), "").toUpperCase(Locale.ROOT))) {
                filtered.add(row);
            }
        }
        return filtered;
    }

    private List<Map<String, Object>> normalizeTrace(List<Map<String, Object>> trace) {
        List<Map<String, Object>> normalized = new ArrayList<>();
        for (Map<String, Object> item : trace) {
            Map<String, Object> row = mutableMap(item);
            row.putIfAbsent("timestamp", OffsetDateTime.now().toString());
            row.putIfAbsent("agent", stringValue(row.getOrDefault("stage", "agent")).toLowerCase(Locale.ROOT).replace('_', '-'));
            normalized.add(row);
        }
        return normalized;
    }

    private void addTrace(List<Map<String, Object>> trace, String stage, String status, String summary, int progress, Map<String, Object> metadata) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("stage", stage);
        row.put("status", status);
        row.put("summary", summary);
        row.put("progress", progress);
        row.put("agent", stage.toLowerCase(Locale.ROOT).replace('_', '-'));
        row.put("timestamp", OffsetDateTime.now().toString());
        row.put("metadata", metadata);
        trace.add(row);
    }

    private void logShortStage(CreatorShortVideo video, String stage, Map<String, Object> summary) {
        if (video == null) {
            return;
        }
        log.info(
                "Generate Shorts stage complete stage={} jobId={} videoId={} tenantId={} userId={} summary={}",
                stage,
                video.getGenerationJobId(),
                video.getId(),
                video.getTenantId(),
                video.getUserId(),
                summary == null ? Map.of() : summary
        );
    }

    private Map<String, Object> logSummary(Object... pairs) {
        Map<String, Object> summary = new LinkedHashMap<>();
        if (pairs == null) {
            return summary;
        }
        for (int index = 0; index + 1 < pairs.length; index += 2) {
            Object key = pairs[index];
            if (key != null) {
                summary.put(String.valueOf(key), pairs[index + 1]);
            }
        }
        return summary;
    }

    private void persistTranscriptStageSnapshot(
            CreatorShortVideo video,
            String stage,
            List<Map<String, Object>> transcript,
            Map<String, Object> graph,
            List<Map<String, Object>> trace,
            Map<String, Object> stageMetadata
    ) {
        if (video == null || video.getId() == null) {
            return;
        }
        List<Map<String, Object>> safeTranscript = transcript == null ? List.of() : new ArrayList<>(transcript);
        if (safeTranscript.isEmpty()) {
            return;
        }
        String normalizedStage = defaultString(stage, "UNKNOWN").toUpperCase(Locale.ROOT);
        String savedAt = OffsetDateTime.now().toString();
        Map<String, Object> safeGraph = graph == null || graph.isEmpty()
                ? transcriptTimelineGraph(safeTranscript, "durable_" + normalizedStage.toLowerCase(Locale.ROOT))
                : new LinkedHashMap<>(graph);

        Map<String, Object> transcriptPayload = mutableMap(video.getTranscriptPayload());
        transcriptPayload.put("nodes", safeTranscript);
        transcriptPayload.put("analysisSource", "durable_" + normalizedStage.toLowerCase(Locale.ROOT));
        transcriptPayload.put("snapshotStage", normalizedStage);
        transcriptPayload.put("savedAt", savedAt);
        video.setTranscriptPayload(transcriptPayload);
        video.setGraphPayload(safeGraph);

        if (trace != null && !trace.isEmpty()) {
            Map<String, Object> tracePayload = new LinkedHashMap<>();
            tracePayload.put("stages", new ArrayList<>(trace));
            tracePayload.put("pipeline", PIPELINE_STAGES);
            tracePayload.put("snapshotStage", normalizedStage);
            tracePayload.put("savedAt", savedAt);
            video.setTracePayload(tracePayload);
        }

        Map<String, Object> metadata = mutableMap(video.getMetadata());
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("stage", normalizedStage);
        snapshot.put("savedAt", savedAt);
        snapshot.put("nodeCount", safeTranscript.size());
        snapshot.put("graphNodeCount", listOfMaps(safeGraph.get("nodes")).size());
        snapshot.put("graphEdgeCount", listOfMaps(safeGraph.get("edges")).size());
        snapshot.put("metadata", stageMetadata == null ? Map.of() : new LinkedHashMap<>(stageMetadata));
        metadata.put("transcriptSnapshot", snapshot);
        metadata.put("latestDurableStage", normalizedStage);
        metadata.put("latestDurableStageAt", savedAt);
        video.setMetadata(metadata);
        saveShortVideoWithTransientRetry(video, "persist_" + normalizedStage.toLowerCase(Locale.ROOT) + "_snapshot", video.getGenerationJobId());
    }

    private void persistSceneTimelineStageSnapshot(
            CreatorShortVideo video,
            String stage,
            ShortSceneAnalysisService.SceneAnalysisResult sceneAnalysis,
            Map<String, Object> sceneTimeline,
            Map<String, Object> sceneCritic,
            List<Map<String, Object>> trace,
            Map<String, Object> stageMetadata
    ) {
        if (video == null || video.getId() == null || sceneAnalysis == null) {
            return;
        }
        String normalizedStage = defaultString(stage, "UNKNOWN").toUpperCase(Locale.ROOT);
        String savedAt = OffsetDateTime.now().toString();
        List<Map<String, Object>> transcript = sceneAnalysis.transcript() == null ? List.of() : new ArrayList<>(sceneAnalysis.transcript());
        if (!transcript.isEmpty()) {
            Map<String, Object> transcriptPayload = mutableMap(video.getTranscriptPayload());
            transcriptPayload.put("nodes", transcript);
            transcriptPayload.put("analysisSource", "durable_" + normalizedStage.toLowerCase(Locale.ROOT));
            transcriptPayload.put("snapshotStage", normalizedStage);
            transcriptPayload.put("savedAt", savedAt);
            video.setTranscriptPayload(transcriptPayload);
            video.setGraphPayload(transcriptTimelineGraph(transcript, "durable_" + normalizedStage.toLowerCase(Locale.ROOT)));
        }
        if (trace != null && !trace.isEmpty()) {
            Map<String, Object> tracePayload = new LinkedHashMap<>();
            tracePayload.put("stages", new ArrayList<>(trace));
            tracePayload.put("pipeline", PIPELINE_STAGES);
            tracePayload.put("snapshotStage", normalizedStage);
            tracePayload.put("savedAt", savedAt);
            video.setTracePayload(tracePayload);
        }

        Map<String, Object> metadata = mutableMap(video.getMetadata());
        Map<String, Object> timelinePayload = sceneTimeline == null || sceneTimeline.isEmpty()
                ? sceneTimeline(transcript, sceneAnalysis.scenes(), sceneAnalysis.frames(), sceneCritic == null ? Map.of() : sceneCritic, Map.of())
                : new LinkedHashMap<>(sceneTimeline);
        Map<String, Object> sceneAnalysisPayload = new LinkedHashMap<>();
        sceneAnalysisPayload.put("mediaBacked", sceneAnalysis.mediaBacked());
        sceneAnalysisPayload.put("metadata", sceneAnalysis.metadata());
        sceneAnalysisPayload.put("sceneCount", sceneAnalysis.scenes().size());
        sceneAnalysisPayload.put("frameCount", sceneAnalysis.frames().size());
        sceneAnalysisPayload.put("scenes", sceneAnalysis.scenes());
        sceneAnalysisPayload.put("frames", sceneAnalysis.frames());

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("stage", normalizedStage);
        snapshot.put("savedAt", savedAt);
        snapshot.put("schema", defaultString(timelinePayload.get("schema"), "timeline_intelligence_v2"));
        snapshot.put("sceneCount", sceneAnalysis.scenes().size());
        snapshot.put("frameCount", sceneAnalysis.frames().size());
        snapshot.put("transcriptNodeCount", transcript.size());
        snapshot.put("hasVisualEvidence", !sceneAnalysis.frames().isEmpty());
        snapshot.put("metadata", stageMetadata == null ? Map.of() : new LinkedHashMap<>(stageMetadata));

        metadata.put("sceneTimeline", timelinePayload);
        metadata.put("sceneAnalysis", sceneAnalysisPayload);
        metadata.put("sceneTimelineSnapshot", snapshot);
        metadata.put("latestDurableStage", normalizedStage);
        metadata.put("latestDurableStageAt", savedAt);
        if (sceneCritic != null && !sceneCritic.isEmpty()) {
            Map<String, Object> deepSceneCritic = mapValue(metadata.get("deepSceneCritic"));
            deepSceneCritic.put("sceneCritic", sceneCritic);
            deepSceneCritic.put("snapshotStage", normalizedStage);
            deepSceneCritic.put("savedAt", savedAt);
            metadata.put("deepSceneCritic", deepSceneCritic);
        }
        video.setMetadata(metadata);
        saveShortVideoWithTransientRetry(video, "persist_" + normalizedStage.toLowerCase(Locale.ROOT) + "_snapshot", video.getGenerationJobId());
    }

    private Map<String, Object> sceneTimeline(
            List<Map<String, Object>> transcript,
            List<Map<String, Object>> scenes,
            List<Map<String, Object>> frames,
            Map<String, Object> sceneCritic,
            Map<String, Object> continuityCritic
    ) {
        List<Map<String, Object>> safeScenes = scenes == null ? List.of() : scenes;
        List<Map<String, Object>> safeTranscript = transcript == null ? List.of() : transcript;
        List<Map<String, Object>> safeFrames = frames == null ? List.of() : frames;
        Map<String, Map<String, Object>> frameLookup = frameMapById(safeFrames);
        List<Map<String, Object>> timeline = new ArrayList<>();
        double timelineEnd = 0.0;
        for (int index = 0; index < safeScenes.size(); index++) {
            Map<String, Object> scene = safeScenes.get(index) == null ? new LinkedHashMap<>() : safeScenes.get(index);
            Map<String, Object> previous = index == 0 ? new LinkedHashMap<>() : safeScenes.get(index - 1);
            Map<String, Object> next = index + 1 >= safeScenes.size() ? new LinkedHashMap<>() : safeScenes.get(index + 1);
            String sceneId = defaultString(firstNonEmpty(scene.get("id"), scene.get("sceneId")), "scene-%03d".formatted(index + 1));
            double start = roundSeconds(doubleValue(firstNonEmpty(scene.get("start"), scene.get("sourceStart")), 0.0));
            double end = roundSeconds(Math.max(start + 0.1, doubleValue(firstNonEmpty(scene.get("end"), scene.get("sourceEnd")), start + 1.0)));
            List<Map<String, Object>> sceneTranscript = transcriptForScene(safeTranscript, sceneId, start, end);
            List<Map<String, Object>> sceneFrames = framesForScene(scene, safeFrames, frameLookup);
            List<Map<String, Object>> sceneDialogue = dialogueNodes(sceneTranscript);
            List<Map<String, Object>> sceneSpeakerTurns = speakerTurns(sceneTranscript);
            Map<String, Object> sceneQuestionAnswer = questionAnswerForTimeline(sceneTranscript, scene);

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", sceneId);
            item.put("index", intValue(scene.get("index"), index + 1));
            item.put("label", defaultString(scene.get("label"), "Scene " + (index + 1)));
            item.put("start", start);
            item.put("end", end);
            item.put("durationSeconds", roundSeconds(end - start));
            item.put("transcript", sceneTranscript);
            item.put("dialogue", sceneDialogue);
            item.put("speakerTurns", sceneSpeakerTurns);
            item.put("questionAnswer", sceneQuestionAnswer);
            item.put("activeSpeaker", dominantSpeaker(sceneTranscript, scene));
            item.put("transcriptNodeCount", sceneTranscript.size());
            item.put("frames", sceneFrames);
            item.put("frameIds", sceneFrames.stream().map(frame -> defaultString(frame.get("id"), "")).filter(id -> !id.isBlank()).toList());
            item.put("visualAnalysis", visualSceneAnalysis(scene, sceneFrames, sceneCritic));
            item.put("continuity", continuityForScene(scene, sceneTranscript, sceneFrames, sceneCritic, continuityCritic));
            item.put("transitionIn", sceneTransition(previous, scene, "IN", index));
            item.put("transitionOut", sceneTransition(scene, next, "OUT", index + 1));
            item.put("json", Map.of(
                    "scene", scene,
                    "transcript", sceneTranscript,
                    "dialogue", sceneDialogue,
                    "speakerTurns", sceneSpeakerTurns,
                    "questionAnswer", sceneQuestionAnswer,
                    "frames", sceneFrames
            ));
            timeline.add(item);
            timelineEnd = Math.max(timelineEnd, end);
        }

        List<Map<String, Object>> dialogue = dialogueNodes(safeTranscript);
        List<Map<String, Object>> speakerTurns = speakerTurns(safeTranscript);
        List<Map<String, Object>> questionAnswerTurns = questionAnswerTurns(safeTranscript, safeScenes);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "creator_scene_timeline");
        metadata.put("schema", "timeline_intelligence_v2");
        metadata.put("generatedAt", OffsetDateTime.now().toString());
        metadata.put("sceneCount", timeline.size());
        metadata.put("frameCount", safeFrames.size());
        metadata.put("transcriptNodeCount", safeTranscript.size());
        metadata.put("dialogueCount", dialogue.size());
        metadata.put("speakerTurnCount", speakerTurns.size());
        metadata.put("questionAnswerCount", questionAnswerTurns.size());
        metadata.put("hasVisualEvidence", !safeFrames.isEmpty());
        metadata.put("hasContinuityCritic", continuityCritic != null && !continuityCritic.isEmpty());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source", "creator_scene_timeline");
        payload.put("schema", "timeline_intelligence_v2");
        payload.put("timelineStart", 0);
        payload.put("timelineEnd", roundSeconds(timelineEnd));
        payload.put("dialogue", dialogue);
        payload.put("speakerTurns", speakerTurns);
        payload.put("questionAnswerTurns", questionAnswerTurns);
        payload.put("scenes", timeline);
        payload.put("json", Map.of(
                "sceneMap", timeline,
                "frames", safeFrames,
                "dialogue", dialogue,
                "speakerTurns", speakerTurns,
                "questionAnswerTurns", questionAnswerTurns
        ));
        payload.put("metadata", metadata);
        return payload;
    }

    private List<Map<String, Object>> dialogueNodes(List<Map<String, Object>> transcript) {
        List<Map<String, Object>> dialogue = new ArrayList<>();
        int index = 1;
        for (Map<String, Object> node : transcript == null ? List.<Map<String, Object>>of() : transcript) {
            if (node == null) {
                continue;
            }
            String text = nodeText(node);
            if (text.isBlank()) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", defaultString(firstNonEmpty(node.get("id"), node.get("nodeId")), "d-%03d".formatted(index)));
            item.put("index", index);
            item.put("start", nodeStartSeconds(node));
            item.put("end", nodeEndSeconds(node));
            item.put("speaker", speakerLabel(node));
            item.put("text", text);
            item.put("isQuestion", questionLike(text));
            item.put("sceneId", defaultString(node.get("sceneId"), ""));
            Map<String, Object> speakerFocus = mapValue(node.get("speakerFocus"));
            item.put("speakerFocus", speakerFocus.isEmpty() ? speakerFocusMap(speakerLabel(node)) : speakerFocus);
            dialogue.add(item);
            index++;
        }
        return dialogue;
    }

    private List<Map<String, Object>> speakerTurns(List<Map<String, Object>> transcript) {
        List<Map<String, Object>> dialogue = dialogueNodes(transcript);
        List<Map<String, Object>> turns = new ArrayList<>();
        Map<String, Object> current = new LinkedHashMap<>();
        List<String> dialogueIds = new ArrayList<>();
        int index = 1;
        for (Map<String, Object> node : dialogue) {
            String speaker = defaultString(node.get("speaker"), "unknown");
            if (current.isEmpty() || speaker.equals(defaultString(current.get("speaker"), ""))) {
                if (current.isEmpty()) {
                    current.put("id", "turn-%03d".formatted(index));
                    current.put("index", index);
                    current.put("speaker", speaker);
                    current.put("start", node.get("start"));
                    current.put("text", "");
                    current.put("isQuestion", false);
                    dialogueIds = new ArrayList<>();
                }
                current.put("end", node.get("end"));
                current.put("text", joinText(defaultString(current.get("text"), ""), defaultString(node.get("text"), "")));
                current.put("isQuestion", booleanValue(current.get("isQuestion"), false) || booleanValue(node.get("isQuestion"), false));
                dialogueIds.add(defaultString(node.get("id"), ""));
                current.put("dialogueIds", new ArrayList<>(dialogueIds));
            } else {
                turns.add(current);
                index++;
                current = new LinkedHashMap<>();
                dialogueIds = new ArrayList<>();
                current.put("id", "turn-%03d".formatted(index));
                current.put("index", index);
                current.put("speaker", speaker);
                current.put("start", node.get("start"));
                current.put("end", node.get("end"));
                current.put("text", defaultString(node.get("text"), ""));
                current.put("isQuestion", booleanValue(node.get("isQuestion"), false));
                dialogueIds.add(defaultString(node.get("id"), ""));
                current.put("dialogueIds", new ArrayList<>(dialogueIds));
            }
        }
        if (!current.isEmpty()) {
            turns.add(current);
        }
        return turns;
    }

    private Map<String, Object> questionAnswerForTimeline(List<Map<String, Object>> transcript, Map<String, Object> scene) {
        List<Map<String, Object>> turns = questionAnswerTurns(transcript, List.of(scene == null ? Map.of() : scene));
        return turns.isEmpty() ? Map.of(
                "hasQuestionAnswer", false,
                "question", "",
                "answer", "",
                "questionSpeaker", "",
                "answerSpeaker", ""
        ) : turns.get(0);
    }

    private List<Map<String, Object>> questionAnswerTurns(List<Map<String, Object>> transcript, List<Map<String, Object>> scenes) {
        List<Map<String, Object>> turns = speakerTurns(transcript);
        List<Map<String, Object>> pairs = new ArrayList<>();
        int index = 1;
        for (int i = 0; i < turns.size(); i++) {
            Map<String, Object> turn = turns.get(i);
            if (!booleanValue(turn.get("isQuestion"), false)) {
                continue;
            }
            Map<String, Object> answerTurn = nextAnswerTurn(turns, i);
            Map<String, Object> pair = new LinkedHashMap<>();
            pair.put("id", "qa-%03d".formatted(index));
            pair.put("index", index);
            pair.put("hasQuestionAnswer", !answerTurn.isEmpty());
            pair.put("sceneId", sceneIdForRange(scenes, doubleValue(turn.get("start"), 0.0), doubleValue(answerTurn.getOrDefault("end", turn.get("end")), doubleValue(turn.get("end"), 0.0))));
            pair.put("questionSpeaker", defaultString(turn.get("speaker"), ""));
            pair.put("question", defaultString(turn.get("text"), ""));
            pair.put("questionStart", turn.get("start"));
            pair.put("questionEnd", turn.get("end"));
            pair.put("answerSpeaker", defaultString(answerTurn.get("speaker"), ""));
            pair.put("answer", defaultString(answerTurn.get("text"), ""));
            pair.put("answerStart", answerTurn.get("start"));
            pair.put("answerEnd", answerTurn.get("end"));
            pair.put("speakerFocus", Map.of(
                    "question", speakerFocusForSpeaker(defaultString(turn.get("speaker"), "")),
                    "answer", speakerFocusForSpeaker(defaultString(answerTurn.get("speaker"), ""))
            ));
            pairs.add(pair);
            index++;
        }
        return pairs;
    }

    private Map<String, Object> nextAnswerTurn(List<Map<String, Object>> turns, int questionIndex) {
        String questionSpeaker = defaultString(turns.get(questionIndex).get("speaker"), "");
        for (int index = questionIndex + 1; index < turns.size(); index++) {
            Map<String, Object> candidate = turns.get(index);
            String speaker = defaultString(candidate.get("speaker"), "");
            if (!speaker.equals(questionSpeaker) || !booleanValue(candidate.get("isQuestion"), false)) {
                return candidate;
            }
        }
        return new LinkedHashMap<>();
    }

    private String sceneIdForRange(List<Map<String, Object>> scenes, double start, double end) {
        double midpoint = start + Math.max(0.0, end - start) / 2.0;
        Map<String, Object> scene = sceneAtTimestamp(scenes, midpoint);
        return defaultString(firstNonEmpty(scene.get("id"), scene.get("sceneId")), "");
    }

    private String dominantSpeaker(List<Map<String, Object>> transcript, Map<String, Object> scene) {
        String sceneSpeaker = defaultString(firstNonEmpty(scene == null ? null : scene.get("activeSpeaker"), scene == null ? null : scene.get("speaker")), "");
        if (!sceneSpeaker.isBlank()) {
            return sceneSpeaker;
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Map<String, Object> node : transcript == null ? List.<Map<String, Object>>of() : transcript) {
            String speaker = speakerLabel(node);
            if (!speaker.isBlank()) {
                counts.put(speaker, counts.getOrDefault(speaker, 0) + 1);
            }
        }
        String best = "";
        int bestCount = 0;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > bestCount) {
                best = entry.getKey();
                bestCount = entry.getValue();
            }
        }
        return best;
    }

    private String speakerLabel(Map<String, Object> node) {
        return defaultString(firstNonEmpty(
                node == null ? null : node.get("speaker"),
                node == null ? null : node.get("activeSpeaker"),
                node == null ? null : node.get("role")
        ), "unknown");
    }

    private String speakerFocusForSpeaker(String speaker) {
        String normalized = defaultString(speaker, "").toLowerCase(Locale.ROOT);
        if (normalized.contains("1") || normalized.contains("host") || normalized.contains("interviewer") || normalized.contains("question")) {
            return "left";
        }
        if (normalized.contains("2") || normalized.contains("guest") || normalized.contains("answer")) {
            return "right";
        }
        return "center";
    }

    private Map<String, Object> speakerFocusMap(String speaker) {
        String safeSpeaker = defaultString(speaker, "unknown");
        String anchor = speakerFocusForSpeaker(safeSpeaker);
        return Map.of(
                "speaker", safeSpeaker,
                "cropAnchor", anchor,
                "layoutMode", "speaker_focus_crop",
                "source", "transcript_speaker_turns",
                "confidence", "center".equals(anchor) ? 0.45 : 0.72
        );
    }

    private String joinText(String left, String right) {
        String safeLeft = defaultString(left, "").trim();
        String safeRight = defaultString(right, "").trim();
        if (safeLeft.isBlank()) {
            return safeRight;
        }
        if (safeRight.isBlank()) {
            return safeLeft;
        }
        return safeLeft + " " + safeRight;
    }

    private Map<String, Map<String, Object>> frameMapById(List<Map<String, Object>> frames) {
        Map<String, Map<String, Object>> lookup = new LinkedHashMap<>();
        for (Map<String, Object> frame : frames == null ? List.<Map<String, Object>>of() : frames) {
            if (frame == null) {
                continue;
            }
            String id = defaultString(firstNonEmpty(frame.get("id"), frame.get("frameId")), "");
            if (!id.isBlank()) {
                lookup.put(id, frame);
            }
        }
        return lookup;
    }

    private List<Map<String, Object>> transcriptForScene(List<Map<String, Object>> transcript, String sceneId, double start, double end) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> node : transcript == null ? List.<Map<String, Object>>of() : transcript) {
            if (node == null) {
                continue;
            }
            String nodeSceneId = defaultString(node.get("sceneId"), "");
            double nodeStart = doubleValue(firstNonEmpty(node.get("start"), node.get("sourceStart")), 0.0);
            double nodeEnd = doubleValue(firstNonEmpty(node.get("end"), node.get("sourceEnd")), nodeStart);
            boolean sameScene = !sceneId.isBlank() && sceneId.equals(nodeSceneId);
            boolean overlaps = nodeEnd >= start && nodeStart <= end;
            if (sameScene || overlaps) {
                result.add(new LinkedHashMap<>(node));
            }
        }
        return result;
    }

    private List<Map<String, Object>> framesForScene(
            Map<String, Object> scene,
            List<Map<String, Object>> frames,
            Map<String, Map<String, Object>> frameLookup
    ) {
        List<Map<String, Object>> result = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        Object sceneFrameIds = scene == null ? null : scene.get("frameIds");
        if (sceneFrameIds instanceof List<?> list) {
            for (Object item : list) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    ids.add(String.valueOf(item));
                }
            }
        }
        for (String id : ids) {
            Map<String, Object> frame = frameLookup == null ? null : frameLookup.get(id);
            if (frame != null) {
                result.add(new LinkedHashMap<>(frame));
            }
        }
        if (!result.isEmpty()) {
            return result;
        }
        String sceneId = defaultString(scene == null ? null : firstNonEmpty(scene.get("id"), scene.get("sceneId")), "");
        for (Map<String, Object> frame : frames == null ? List.<Map<String, Object>>of() : frames) {
            if (sceneId.equals(defaultString(frame.get("sceneId"), ""))) {
                result.add(new LinkedHashMap<>(frame));
            }
        }
        return result;
    }

    private Map<String, Object> visualSceneAnalysis(
            Map<String, Object> scene,
            List<Map<String, Object>> frames,
            Map<String, Object> sceneCritic
    ) {
        Map<String, Object> visual = new LinkedHashMap<>();
        visual.put("scene", scene == null ? Map.of() : scene);
        visual.put("representativeFrameId", defaultString(scene == null ? null : scene.get("representativeFrameId"), ""));
        visual.put("frameCount", frames == null ? 0 : frames.size());
        visual.put("hasVisualEvidence", frames != null && !frames.isEmpty());
        visual.put("source", defaultString(scene == null ? null : scene.get("source"), "scene_analysis"));
        visual.put("criticStatus", defaultString(sceneCritic == null ? null : sceneCritic.get("status"), ""));
        visual.put("criticConfidence", doubleValue(sceneCritic == null ? null : sceneCritic.get("confidence"), 0.0));
        visual.put("critic", sceneCritic == null ? Map.of() : sceneCritic);
        return visual;
    }

    private Map<String, Object> continuityForScene(
            Map<String, Object> scene,
            List<Map<String, Object>> transcript,
            List<Map<String, Object>> frames,
            Map<String, Object> sceneCritic,
            Map<String, Object> continuityCritic
    ) {
        Map<String, Object> continuity = new LinkedHashMap<>();
        int transcriptCount = transcript == null ? 0 : transcript.size();
        int frameCount = frames == null ? 0 : frames.size();
        continuity.put("status", frameCount == 0 ? "WARN" : "READY");
        continuity.put("coverage", Map.of(
                "transcriptNodeCount", transcriptCount,
                "frameCount", frameCount,
                "hasVisualEvidence", frameCount > 0
        ));
        continuity.put("sceneCriticStatus", defaultString(sceneCritic == null ? null : sceneCritic.get("status"), ""));
        continuity.put("continuityCritic", continuityCritic == null ? Map.of() : continuityCritic);
        continuity.put("transitionRisk", frameCount == 0 ? "VISUAL_EVIDENCE_WEAK" : "NORMAL");
        continuity.put("notes", frameCount == 0
                ? "No representative frame was available for this scene; keep human review enabled."
                : "Scene has transcript and visual evidence for timeline review.");
        return continuity;
    }

    private Map<String, Object> sceneTransition(Map<String, Object> left, Map<String, Object> right, String direction, int index) {
        if (right == null || right.isEmpty()) {
            return Map.of("type", "NONE", "direction", direction, "reason", "No adjacent scene.");
        }
        double leftEnd = left == null || left.isEmpty()
                ? doubleValue(firstNonEmpty(right.get("start"), right.get("sourceStart")), 0.0)
                : doubleValue(firstNonEmpty(left.get("end"), left.get("sourceEnd")), 0.0);
        double rightStart = doubleValue(firstNonEmpty(right.get("start"), right.get("sourceStart")), leftEnd);
        double gap = roundSeconds(rightStart - leftEnd);
        Map<String, Object> transition = new LinkedHashMap<>();
        transition.put("id", "transition-%03d-%s".formatted(Math.max(1, index), direction.toLowerCase(Locale.ROOT)));
        transition.put("direction", direction);
        transition.put("type", transitionKind(gap));
        transition.put("fromSceneId", defaultString(left == null ? null : firstNonEmpty(left.get("id"), left.get("sceneId")), ""));
        transition.put("toSceneId", defaultString(firstNonEmpty(right.get("id"), right.get("sceneId")), ""));
        transition.put("at", roundSeconds(rightStart));
        transition.put("gapSeconds", gap);
        transition.put("reason", Math.abs(gap) <= 0.25
                ? "Adjacent visual scene boundary."
                : (gap > 0 ? "Small timeline gap between scenes." : "Scene windows overlap around boundary."));
        return transition;
    }

    private String transitionKind(double gapSeconds) {
        if (Math.abs(gapSeconds) <= 0.25) {
            return "CUT";
        }
        return gapSeconds > 0 ? "GAP" : "OVERLAP";
    }

    private boolean pauseIfRequested(
            CreatorShortVideo video,
            String stage,
            List<Map<String, Object>> transcript,
            Map<String, Object> graph,
            List<Map<String, Object>> trace,
            Map<String, Object> outputPatch
    ) {
        UUID jobId = video == null ? null : video.getGenerationJobId();
        if (jobId == null) {
            return false;
        }
        boolean pauseRequested = generationJobService.isPauseRequested(jobId);
        Map<String, Object> jobOutput = generationJobService.findGenerationJob(jobId)
                .map(CreatorGenerationJob::getOutputPayload)
                .map(this::mutableMap)
                .orElseGet(LinkedHashMap::new);
        boolean manualStepMode = isManualStepMode(video, jobOutput, Map.of());
        boolean manualStepCheckpoint = !pauseRequested && shouldPauseManualStepAtStage(stage, jobOutput, manualStepMode);
        if (!pauseRequested && !manualStepCheckpoint) {
            return false;
        }
        List<Map<String, Object>> safeTranscript = transcript == null ? List.of() : transcript;
        Map<String, Object> safeGraph = graph == null ? new LinkedHashMap<>() : new LinkedHashMap<>(graph);
        List<Map<String, Object>> safeTrace = trace == null ? List.of() : trace;

        Map<String, Object> transcriptPayload = mutableMap(video.getTranscriptPayload());
        if (!safeTranscript.isEmpty()) {
            transcriptPayload.put("nodes", safeTranscript);
            transcriptPayload.put("analysisSource", "paused_" + defaultString(stage, "stage").toLowerCase(Locale.ROOT));
            video.setTranscriptPayload(transcriptPayload);
        }
        if (!safeGraph.isEmpty()) {
            video.setGraphPayload(safeGraph);
        } else if (!safeTranscript.isEmpty()) {
            safeGraph = transcriptTimelineGraph(safeTranscript, "paused_" + defaultString(stage, "stage").toLowerCase(Locale.ROOT));
            video.setGraphPayload(safeGraph);
        }
        if (!safeTrace.isEmpty()) {
            Map<String, Object> tracePayload = new LinkedHashMap<>();
            tracePayload.put("stages", safeTrace);
            tracePayload.put("pipeline", PIPELINE_STAGES);
            video.setTracePayload(tracePayload);
        }

        Map<String, Object> metadata = mutableMap(video.getMetadata());
        Map<String, Object> pauseState = new LinkedHashMap<>();
        pauseState.put("requested", false);
        pauseState.put("paused", true);
        pauseState.put("pausedAt", OffsetDateTime.now().toString());
        pauseState.put("stage", defaultString(stage, "UNKNOWN"));
        pauseState.put("kind", manualStepCheckpoint ? "MANUAL_STEP_CHECKPOINT" : "USER_REQUESTED");
        pauseState.put("manualStepMode", manualStepMode);
        pauseState.put("nextStage", nextManualStepStage(stage));
        pauseState.put("transcriptNodeCount", safeTranscript.size());
        pauseState.put("graphNodeCount", listOfMaps(safeGraph.get("nodes")).size());
        metadata.put("pauseState", pauseState);
        if (manualStepMode) {
            metadata.put("executionMode", "MANUAL_STEP");
        }
        video.setStatus("PAUSED");
        video.setMetadata(metadata);
        video.setCompletedAt(null);
        CreatorShortVideo saved = videoRepository.saveAndFlush(video);

        Map<String, Object> pauseOutput = new LinkedHashMap<>();
        pauseOutput.put("shortVideoId", saved.getId().toString());
        pauseOutput.put("status", "PAUSED");
        pauseOutput.put("activeStage", defaultString(stage, "PAUSED"));
        pauseOutput.put("pausedStage", defaultString(stage, "UNKNOWN"));
        pauseOutput.put("transcriptNodeCount", safeTranscript.size());
        if (manualStepMode) {
            pauseOutput.put("manualStepMode", true);
            pauseOutput.put("executionMode", "MANUAL_STEP");
            pauseOutput.put("manualAdvanceFromStage", defaultString(stage, ""));
            pauseOutput.put("manualNextStage", nextManualStepStage(stage));
            pauseOutput.put("manualStepPausedAt", OffsetDateTime.now().toString());
        }
        if (!safeTranscript.isEmpty()) {
            pauseOutput.put("transcript", safeTranscript);
            pauseOutput.put("transcriptGraph", transcriptTimelineGraph(safeTranscript, "paused_" + defaultString(stage, "stage").toLowerCase(Locale.ROOT)));
        }
        if (!safeGraph.isEmpty()) {
            pauseOutput.put("graph", safeGraph);
        }
        if (!safeTrace.isEmpty()) {
            pauseOutput.put("trace", safeTrace);
        }
        if (outputPatch != null && !outputPatch.isEmpty()) {
            pauseOutput.putAll(outputPatch);
        }
        generationJobService.pauseGenerationJob(
                jobId,
                stage,
                "Generation paused at " + defaultString(stage, "the current stage") + ". Timeline and story edits can be saved before resume.",
                pauseOutput
        );
        log.info(
                "Generate Shorts paused at safe stage jobId={} videoId={} stage={} transcriptNodes={} graphNodes={}",
                jobId,
                saved.getId(),
                stage,
                safeTranscript.size(),
                listOfMaps(safeGraph.get("nodes")).size()
        );
        return true;
    }

    private boolean isManualStepMode(CreatorShortVideo video, Map<String, Object> jobOutput, Map<String, Object> requestPayload) {
        Map<String, Object> safeJobOutput = mutableMap(jobOutput);
        Map<String, Object> safeRequestPayload = mutableMap(requestPayload);
        Map<String, Object> settings = mutableMap(video == null ? null : video.getSettings());
        Map<String, Object> metadata = mutableMap(video == null ? null : video.getMetadata());
        return booleanValue(safeRequestPayload.get("manualStepMode"), false)
                || booleanValue(safeJobOutput.get("manualStepMode"), false)
                || booleanValue(settings.get("manualStepMode"), false)
                || "MANUAL_STEP".equals(normalizeExecutionMode(firstNonEmpty(
                        safeRequestPayload.get("executionMode"),
                        safeJobOutput.get("executionMode"),
                        settings.get("executionMode"),
                        metadata.get("executionMode")
                )));
    }

    private boolean isExplicitAutoResume(Map<String, Object> requestPayload) {
        Map<String, Object> safeRequestPayload = mutableMap(requestPayload);
        Object executionMode = safeRequestPayload.get("executionMode");
        Object manualStepMode = safeRequestPayload.get("manualStepMode");
        return (executionMode != null && "AUTO".equals(normalizeExecutionMode(executionMode)))
                || (manualStepMode != null && !booleanValue(manualStepMode, false));
    }

    private boolean shouldPauseManualStepAtStage(String stage, Map<String, Object> jobOutput, boolean manualStepMode) {
        if (!manualStepMode) {
            return false;
        }
        Map<String, Object> safeJobOutput = mutableMap(jobOutput);
        int stageIndex = manualStepStageIndex(stage);
        if (stageIndex < 0) {
            return false;
        }
        String advanceFrom = defaultString(firstNonEmpty(
                safeJobOutput.get("manualAdvanceFromStage"),
                safeJobOutput.get("manualStepAdvanceFromStage"),
                safeJobOutput.get("pausedStage")
        ), "");
        if (advanceFrom.isBlank()) {
            return true;
        }
        int advanceIndex = manualStepStageIndex(advanceFrom);
        return advanceIndex < 0 || stageIndex > advanceIndex;
    }

    private int manualStepStageIndex(Object stage) {
        String normalized = defaultString(stage, "").trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        int index = MANUAL_STEP_STAGES.indexOf(normalized);
        return index >= 0 ? index : PIPELINE_STAGES.indexOf(normalized);
    }

    private String nextManualStepStage(Object stage) {
        int index = manualStepStageIndex(stage);
        if (index < 0 || index + 1 >= MANUAL_STEP_STAGES.size()) {
            return "";
        }
        return MANUAL_STEP_STAGES.get(index + 1);
    }

    private CompletableFuture<ShortSceneAnalysisService.VisualSceneAnalysisResult> startSceneVisualPrep(
            CreatorShortVideo video,
            CreatorAsset sourceAsset,
            Path sourceVideoPath,
            String reason
    ) {
        UUID jobId = video == null ? null : video.getGenerationJobId();
        if (jobId != null) {
            generationJobService.updateGenerationJobProgress(jobId, 34, "Preparing Gemini scene map in parallel with transcript work", Map.of(
                    "activeStage", "TRANSCRIPT",
                    "sceneAnalysisMode", "background_gemini_scene_map",
                    "sceneMapSource", "gemini_video_scene_map",
                    "sceneVisualPrepReason", defaultString(reason, "visual_continuity"),
                    "shortVideoId", video.getId() == null ? "" : video.getId().toString()
            ));
        }
        return CompletableFuture.supplyAsync(() -> {
            long startedAt = System.nanoTime();
            try {
                log.info(
                        "Shorts background Gemini scene map prep started jobId={} videoId={} sourceAssetId={} reason={}",
                        jobId,
                        video == null ? null : video.getId(),
                        sourceAsset == null ? null : sourceAsset.getId(),
                        reason
                );
                ShortSceneAnalysisService.VisualSceneAnalysisResult result = prepareVisualAnalysisGeminiFirst(
                        video,
                        sourceAsset,
                        sourceVideoPath,
                        List.of(),
                        reason
                );
                log.info(
                        "Shorts background Gemini scene map prep complete jobId={} videoId={} scenes={} frames={} source={} durationMs={}",
                        jobId,
                        video == null ? null : video.getId(),
                        result.scenes().size(),
                        result.frames().size(),
                        defaultString(result.metadata().get("source"), "unknown"),
                        elapsedMillis(startedAt)
                );
                return result;
            } catch (RuntimeException ex) {
                log.warn(
                        "Shorts background scene visual prep failed jobId={} videoId={} message={}",
                        jobId,
                        video == null ? null : video.getId(),
                        ex.getMessage()
                );
                throw ex;
            }
        }, sceneVisualPrepExecutor);
    }

    private ShortSceneAnalysisService.VisualSceneAnalysisResult awaitSceneVisualPrep(
            CreatorShortVideo video,
            CreatorAsset sourceAsset,
            Path sourceVideoPath,
            CompletableFuture<ShortSceneAnalysisService.VisualSceneAnalysisResult> future
    ) {
        if (future == null) {
            return prepareVisualAnalysisGeminiFirst(video, sourceAsset, sourceVideoPath, List.of(), "synchronous_visual_continuity_signal");
        }
        try {
            return future.join();
        } catch (RuntimeException ex) {
            log.warn(
                    "Shorts scene visual prep future was not usable; retrying synchronously jobId={} videoId={} message={}",
                    video == null ? null : video.getGenerationJobId(),
                    video == null ? null : video.getId(),
                    ex.getMessage()
            );
            return prepareVisualAnalysisGeminiFirst(video, sourceAsset, sourceVideoPath, List.of(), "visual_prep_retry_after_future_failure");
        }
    }

    private ShortSceneAnalysisService.VisualSceneAnalysisResult prepareVisualAnalysisGeminiFirst(
            CreatorShortVideo video,
            CreatorAsset sourceAsset,
            Path sourceVideoPath,
            List<Map<String, Object>> transcript,
            String reason
    ) {
        GoogleShortSceneMapService.SceneMapResult geminiSceneMap = geminiSceneMapService.mapScenes(
                video,
                sourceAsset,
                transcript == null ? List.of() : transcript,
                sourceVideoPath
        );
        if (geminiSceneMap.mediaBacked() && !geminiSceneMap.visualAnalysis().scenes().isEmpty()) {
            if (!restoredFromSourceCache(geminiSceneMap.metadata(), geminiSceneMap.tokenMetadata(), geminiSceneMap.costMetadata())) {
                creatorAiService.publishProviderUsageDebit(
                        "SHORTS_GEMINI_SCENE_MAP",
                        geminiSceneMap.provider(),
                        geminiSceneMap.model(),
                        geminiSceneMap.costMetadata(),
                        new CreatorAiService.AiUsageContext(
                                video == null ? null : video.getTenantId(),
                                video == null ? null : video.getUserId(),
                                video == null ? null : video.getProjectId(),
                                video == null ? null : video.getGenerationJobId(),
                                null
                        ),
                        "Gemini timestamped scene map for shorts visual analysis"
                );
            }
            Map<String, Object> metadata = mutableMap(geminiSceneMap.visualAnalysis().metadata());
            metadata.put("primarySceneMapSource", "gemini_video_scene_map");
            metadata.put("sceneVisualPrepReason", defaultString(reason, "visual_continuity"));
            metadata.put("ffmpegSceneDetectionUsed", false);
            return new ShortSceneAnalysisService.VisualSceneAnalysisResult(
                    geminiSceneMap.visualAnalysis().mediaBacked(),
                    geminiSceneMap.visualAnalysis().scenes(),
                    geminiSceneMap.visualAnalysis().frames(),
                    geminiSceneMap.visualAnalysis().trace(),
                    metadata
            );
        }
        log.warn(
                "Gemini scene map unavailable; using transcript-window fallback without FFmpeg frame extraction jobId={} videoId={} reason={} fallbackReason={}",
                video == null ? null : video.getGenerationJobId(),
                video == null ? null : video.getId(),
                defaultString(reason, "visual_continuity"),
                defaultString(geminiSceneMap.metadata().get("reason"), "no usable Gemini scenes")
        );
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "gemini_video_scene_map_unavailable");
        metadata.put("status", "WARN");
        metadata.put("primarySceneMapSource", "none");
        metadata.put("geminiSceneMap", geminiSceneMap.metadata());
        metadata.put("sceneVisualPrepReason", defaultString(reason, "visual_continuity"));
        metadata.put("ffmpegSceneDetectionUsed", false);
        metadata.put("frameExtractionSkipped", true);
        metadata.put("fallback", "transcript_scene_windows");
        metadata.put("reason", defaultString(geminiSceneMap.metadata().get("reason"), "no usable Gemini scenes"));
        metadata.put("generatedAt", OffsetDateTime.now().toString());
        return new ShortSceneAnalysisService.VisualSceneAnalysisResult(
                false,
                List.of(),
                List.of(),
                List.of(traceRow(
                        "VIDEO_ANALYSIS",
                        "WARN",
                        "Gemini full-video scene map was unavailable; backend frame extraction was skipped and transcript windows will be used.",
                        0.42,
                        metadata
                )),
                metadata
        );
    }

    private boolean isFullVideoSceneMapAnalysis(ShortSceneAnalysisService.VisualSceneAnalysisResult visualAnalysis) {
        return visualAnalysis != null
                && visualAnalysis.mediaBacked()
                && !visualAnalysis.scenes().isEmpty()
                && isGeminiVideoSceneMapMetadata(visualAnalysis.metadata());
    }

    private boolean isFullVideoSceneMapAnalysis(ShortSceneAnalysisService.SceneAnalysisResult sceneAnalysis) {
        return sceneAnalysis != null
                && sceneAnalysis.mediaBacked()
                && !sceneAnalysis.scenes().isEmpty()
                && isGeminiVideoSceneMapMetadata(sceneAnalysis.metadata());
    }

    private boolean isGeminiVideoSceneMapMetadata(Map<String, Object> metadata) {
        Map<String, Object> safe = metadata == null ? Map.of() : metadata;
        String source = defaultString(safe.get("source"), "").toLowerCase(Locale.ROOT);
        String primarySource = defaultString(safe.get("primarySceneMapSource"), "").toLowerCase(Locale.ROOT);
        return source.contains("gemini_video_scene_map") || primarySource.contains("gemini_video_scene_map");
    }

    private boolean isSceneMapBackedSceneCritic(GoogleShortSceneCriticService.SceneCriticResult sceneCritic) {
        Map<String, Object> metadata = sceneCritic == null ? Map.of() : mapValue(sceneCritic.metadata());
        Map<String, Object> critic = sceneCritic == null ? Map.of() : mapValue(sceneCritic.sceneCritic());
        String source = defaultString(firstNonEmpty(metadata.get("source"), critic.get("source")), "").toLowerCase(Locale.ROOT);
        String evidenceMode = defaultString(critic.get("visualEvidenceMode"), "").toLowerCase(Locale.ROOT);
        return source.contains("gemini_video_scene_map_scene_critic")
                || evidenceMode.contains("gemini_full_video_scene_map");
    }

    private void cancelSceneVisualPrep(CompletableFuture<?> future, String reason) {
        if (future != null && !future.isDone()) {
            future.cancel(true);
            log.info("Shorts background scene visual prep cancelled reason={}", reason);
        }
    }

    private long elapsedMillis(long startedAtNanos) {
        return Math.max(0, (System.nanoTime() - startedAtNanos) / 1_000_000L);
    }

    private ShortGenerationResponse pausedResponse(CreatorShortVideo video) {
        return toResponse(videoRepository.findById(video.getId()).orElse(video));
    }

    private List<Map<String, Object>> editedTranscriptOverride(UUID jobId, List<Map<String, Object>> fallback, List<Map<String, Object>> trace) {
        Map<String, Object> output = generationJobService.findGenerationJob(jobId)
                .map(CreatorGenerationJob::getOutputPayload)
                .map(this::mutableMap)
                .orElseGet(LinkedHashMap::new);
        if (!booleanValue(output.get("humanTimelineEdited"), false)) {
            return fallback == null ? new ArrayList<>() : fallback;
        }
        List<Map<String, Object>> editedTranscript = listOfMaps(firstNonEmpty(output.get("editedTranscript"), output.get("transcript")));
        if (editedTranscript.isEmpty()) {
            return fallback == null ? new ArrayList<>() : fallback;
        }
        TranscriptRepair repair = repairTranscriptTimeline(editedTranscript);
        if (trace != null) {
            addTrace(
                    trace,
                    "HUMAN_TIMELINE_EDIT",
                    "COMPLETED",
                    "Human timeline edits were applied before downstream generation resumed.",
                    49,
                    repair.metadata()
            );
        }
        log.info(
                "Generate Shorts human transcript override applied jobId={} editedNodes={} repairApplied={}",
                jobId,
                repair.transcript().size(),
                repair.applied()
        );
        return repair.transcript();
    }

    private ShortVideoGraphBuilderService.GraphBuildResult editedGraphOverride(
            UUID jobId,
            ShortVideoGraphBuilderService.GraphBuildResult graphBuild,
            List<Map<String, Object>> transcript,
            List<Map<String, Object>> trace
    ) {
        Map<String, Object> output = generationJobService.findGenerationJob(jobId)
                .map(CreatorGenerationJob::getOutputPayload)
                .map(this::mutableMap)
                .orElseGet(LinkedHashMap::new);
        if (!booleanValue(output.get("humanGraphEdited"), false) && !booleanValue(output.get("humanTimelineEdited"), false)) {
            return graphBuild;
        }
        Map<String, Object> editedGraph = mapValue(firstNonEmpty(output.get("editedGraph"), output.get("graph")));
        if (editedGraph.isEmpty()) {
            editedGraph = transcriptTimelineGraph(transcript, "human_processing_timeline_edit");
        }
        Map<String, Object> metadata = mutableMap(graphBuild == null ? null : graphBuild.metadata());
        metadata.put("humanGraphOverrideApplied", true);
        metadata.put("humanGraphOverrideAt", OffsetDateTime.now().toString());
        metadata.put("nodeCount", listOfMaps(editedGraph.get("nodes")).size());
        metadata.put("edgeCount", listOfMaps(editedGraph.get("edges")).size());
        if (trace != null) {
            addTrace(
                    trace,
                    "HUMAN_GRAPH_EDIT",
                    "COMPLETED",
                    "Human graph edits were applied before story and shorts planning resumed.",
                    64,
                    metadata
            );
        }
        log.info(
                "Generate Shorts human graph override applied jobId={} nodes={} edges={}",
                jobId,
                listOfMaps(editedGraph.get("nodes")).size(),
                listOfMaps(editedGraph.get("edges")).size()
        );
        return new ShortVideoGraphBuilderService.GraphBuildResult(
                editedGraph,
                graphBuild == null ? List.of() : graphBuild.trace(),
                metadata
        );
    }

    private Map<String, Object> humanProcessingEditPayload(UUID jobId) {
        Map<String, Object> output = generationJobService.findGenerationJob(jobId)
                .map(CreatorGenerationJob::getOutputPayload)
                .map(this::mutableMap)
                .orElseGet(LinkedHashMap::new);
        if (!booleanValue(output.get("humanTimelineEdited"), false)
                && !booleanValue(output.get("humanGraphEdited"), false)
                && !booleanValue(output.get("humanStoryEdited"), false)) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> edits = mapValue(output.get("humanProcessingEdits"));
        if (edits.isEmpty()) {
            edits.put("source", "human_processing_timeline_editor");
        }
        List<Map<String, Object>> editedTranscript = listOfMaps(firstNonEmpty(output.get("editedTranscript"), output.get("transcript")));
        Map<String, Object> editedGraph = mapValue(firstNonEmpty(output.get("editedGraph"), output.get("graph")));
        Map<String, Object> storyEdits = mapValue(output.get("storyEdits"));
        List<Map<String, Object>> storyNodes = listOfMaps(output.get("storyNodes"));
        edits.put("humanTimelineEdited", booleanValue(output.get("humanTimelineEdited"), false));
        edits.put("humanGraphEdited", booleanValue(output.get("humanGraphEdited"), false));
        edits.put("humanStoryEdited", booleanValue(output.get("humanStoryEdited"), false));
        edits.put("transcriptNodeCount", editedTranscript.size());
        edits.put("graphNodeCount", listOfMaps(editedGraph.get("nodes")).size());
        if (!storyEdits.isEmpty()) {
            edits.put("storyEdits", storyEdits);
        }
        if (!storyNodes.isEmpty()) {
            edits.put("storyNodes", storyNodes);
        }
        return edits;
    }

    private Map<String, Object> transcriptTimelineGraph(List<Map<String, Object>> transcript, String source) {
        List<Map<String, Object>> input = transcript == null ? List.of() : transcript;
        List<Map<String, Object>> nodes = new ArrayList<>();
        List<Map<String, Object>> edges = new ArrayList<>();
        String previousId = "";
        double timelineEnd = 0.0;
        for (int index = 0; index < input.size(); index++) {
            Map<String, Object> sourceNode = input.get(index) == null ? new LinkedHashMap<>() : input.get(index);
            String text = defaultString(firstNonEmpty(sourceNode.get("transcript"), sourceNode.get("text")), "").trim();
            if (text.isBlank()) {
                continue;
            }
            double start = Math.max(0.0, doubleValue(sourceNode.get("start"), 0.0));
            double end = Math.max(start + 0.2, doubleValue(sourceNode.get("end"), start + 1.0));
            String id = defaultString(sourceNode.get("id"), "n-%04d".formatted(nodes.size() + 1));
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", id);
            node.put("label", defaultString(sourceNode.get("speaker"), "Dialogue") + " " + (nodes.size() + 1));
            node.put("type", "DIALOGUE");
            node.put("ordinal", nodes.size() + 1);
            node.put("start", roundSeconds(start));
            node.put("end", roundSeconds(end));
            node.put("durationSeconds", roundSeconds(end - start));
            node.put("time", timeLabel((int) Math.round(start), (int) Math.round(end)));
            node.put("speaker", defaultString(sourceNode.get("speaker"), "Speaker"));
            node.put("transcript", text);
            node.put("sceneId", defaultString(sourceNode.get("sceneId"), ""));
            node.put("emotion", intValue(sourceNode.get("emotion"), intValue(sourceNode.get("emotionScore"), 0)));
            node.put("motion", intValue(sourceNode.get("motion"), intValue(sourceNode.get("motionScore"), 0)));
            node.put("interestingness", intValue(sourceNode.get("interestingness"), intValue(sourceNode.get("interestingnessScore"), 0)));
            node.put("frames", sourceNode.getOrDefault("frames", List.of()));
            nodes.add(node);
            if (!previousId.isBlank()) {
                Map<String, Object> edge = new LinkedHashMap<>();
                edge.put("from", previousId);
                edge.put("to", id);
                edge.put("type", "NEXT_DIALOGUE");
                edge.put("reason", "Adjacent transcript dialogue in source timeline.");
                edges.add(edge);
            }
            previousId = id;
            timelineEnd = Math.max(timelineEnd, end);
        }
        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("graphViews", List.of("Transcript Timeline Graph", "Conversation Graph", "Story Graph", "Scene Graph", "Compression Graph"));
        graph.put("analysisSource", defaultString(source, "transcript_timeline"));
        graph.put("source", defaultString(source, "transcript_timeline"));
        graph.put("nodes", nodes);
        graph.put("edges", edges);
        graph.put("confidence", nodes.isEmpty() ? 0.0 : 0.9);
        graph.put("timelineStart", 0);
        graph.put("timelineEnd", roundSeconds(timelineEnd));
        graph.put("metadata", Map.of(
                "source", defaultString(source, "transcript_timeline"),
                "nodeType", "DIALOGUE",
                "nodeCount", nodes.size(),
                "edgeCount", edges.size(),
                "graphReadyDuringProcessing", true
        ));
        return graph;
    }

    private TranscriptRepair repairTranscriptTimeline(List<Map<String, Object>> transcript) {
        List<Map<String, Object>> input = transcript == null ? List.of() : transcript;
        List<Map<String, Object>> nodes = new ArrayList<>();
        int dropped = 0;
        for (int index = 0; index < input.size(); index++) {
            Map<String, Object> source = input.get(index);
            Map<String, Object> node = source == null ? new LinkedHashMap<>() : new LinkedHashMap<>(source);
            if (defaultString(node.get("transcript"), "").trim().isBlank()) {
                dropped++;
                continue;
            }
            node.put("_inputOrdinal", index);
            nodes.add(node);
        }
        nodes.sort(java.util.Comparator
                .comparingDouble((Map<String, Object> node) -> doubleValue(node.get("start"), 0.0))
                .thenComparingDouble(node -> doubleValue(node.get("end"), doubleValue(node.get("start"), 0.0) + 0.2)));

        List<Map<String, Object>> repaired = new ArrayList<>();
        int repairCount = dropped;
        double previousStart = -1.0;
        for (int index = 0; index < nodes.size(); index++) {
            Map<String, Object> node = new LinkedHashMap<>(nodes.get(index));
            int originalOrdinal = intValue(node.remove("_inputOrdinal"), index);
            String originalId = defaultString(node.get("id"), "");
            double originalStart = doubleValue(node.get("start"), 0.0);
            double originalEnd = doubleValue(node.get("end"), originalStart + 1.0);
            double start = Math.max(0.0, originalStart);
            double end = Math.max(start + 0.2, originalEnd);
            if (previousStart >= 0 && start + 0.001 < previousStart) {
                start = previousStart;
                end = Math.max(start + 0.2, end);
            }
            String repairedId = "n-%04d".formatted(index + 1);
            boolean changed = originalOrdinal != index
                    || Math.abs(start - originalStart) > 0.001
                    || Math.abs(end - originalEnd) > 0.001
                    || !repairedId.equals(originalId);
            if (changed) {
                repairCount++;
                if (!originalId.isBlank() && !repairedId.equals(originalId)) {
                    node.put("sourceNodeId", originalId);
                }
            }
            node.put("id", repairedId);
            node.put("start", roundSeconds(start));
            node.put("end", roundSeconds(end));
            node.put("time", timeLabel((int) Math.round(start), (int) Math.round(end)));
            repaired.add(node);
            previousStart = start;
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "deterministic_transcript_timeline_repair");
        metadata.put("applied", repairCount > 0);
        metadata.put("inputNodeCount", input.size());
        metadata.put("outputNodeCount", repaired.size());
        metadata.put("repairCount", repairCount);
        metadata.put("droppedEmptyNodes", dropped);
        metadata.put("reason", "sorted_by_start_time_clamped_invalid_ranges_reassigned_node_ids");
        return new TranscriptRepair(repaired, repairCount > 0, metadata);
    }

    private SceneEvidenceRepair repairSceneEvidenceIfUnsafe(
            CreatorShortVideo video,
            ShortSceneAnalysisService.SceneAnalysisResult sceneAnalysis,
            GoogleShortSceneCriticService.SceneCriticResult sceneCritic,
            List<Map<String, Object>> fallbackTranscript
    ) {
        List<String> reasons = sceneEvidenceRiskReasons(sceneAnalysis, sceneCritic);
        if (reasons.isEmpty()) {
            return SceneEvidenceRepair.notApplied();
        }

        List<Map<String, Object>> baseTranscript = sceneAnalysis == null || sceneAnalysis.transcript().isEmpty()
                ? new ArrayList<>(fallbackTranscript == null ? List.of() : fallbackTranscript)
                : new ArrayList<>(sceneAnalysis.transcript());
        TranscriptRepair transcriptRepair = repairTranscriptTimeline(baseTranscript);
        List<Map<String, Object>> transcript = transcriptRepair.transcript();
        List<Map<String, Object>> frames = reassignFramesToConservativeScenes(
                sceneAnalysis == null ? List.of() : sceneAnalysis.frames(),
                transcript
        );
        List<Map<String, Object>> scenes = conservativeScenes(video, transcript, frames);
        frames = reassignFramesToScenes(frames, scenes);
        scenes = attachFrameIdsToScenes(scenes, frames);
        transcript = attachScenesAndFramesToTranscript(transcript, scenes, frames);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "deterministic_scene_evidence_repair");
        metadata.put("applied", true);
        metadata.put("mode", "TRANSCRIPT_LED_REVIEW_REQUIRED");
        metadata.put("reasons", reasons);
        metadata.put("originalSceneCount", sceneAnalysis == null ? 0 : sceneAnalysis.scenes().size());
        metadata.put("originalFrameCount", sceneAnalysis == null ? 0 : sceneAnalysis.frames().size());
        metadata.put("repairedSceneCount", scenes.size());
        metadata.put("repairedFrameCount", frames.size());
        metadata.put("transcriptRepair", transcriptRepair.metadata());

        Map<String, Object> sceneMetadata = sceneAnalysis == null ? new LinkedHashMap<>() : mutableMap(sceneAnalysis.metadata());
        sceneMetadata.put("repair", metadata);
        sceneMetadata.put("source", "conservative_scene_windows");
        sceneMetadata.put("sceneCount", scenes.size());
        sceneMetadata.put("frameCount", frames.size());
        sceneMetadata.put("status", "WARN");

        ShortSceneAnalysisService.SceneAnalysisResult repaired = new ShortSceneAnalysisService.SceneAnalysisResult(
                sceneAnalysis != null && sceneAnalysis.mediaBacked(),
                scenes,
                frames,
                transcript,
                sceneAnalysis == null ? List.of() : sceneAnalysis.trace(),
                sceneMetadata
        );
        return new SceneEvidenceRepair(true, repaired, metadata);
    }

    private List<String> sceneEvidenceRiskReasons(
            ShortSceneAnalysisService.SceneAnalysisResult sceneAnalysis,
            GoogleShortSceneCriticService.SceneCriticResult sceneCritic
    ) {
        List<String> reasons = new ArrayList<>();
        int sceneCount = sceneAnalysis == null ? 0 : sceneAnalysis.scenes().size();
        int frameCount = sceneAnalysis == null ? 0 : sceneAnalysis.frames().size();
        if (isTranscriptFirstSceneStrategy(sceneAnalysis, sceneCritic) && sceneCount > 0) {
            return reasons;
        }
        if (isFullVideoSceneMapAnalysis(sceneAnalysis) && isSceneMapBackedSceneCritic(sceneCritic) && sceneCount > 0) {
            return reasons;
        }
        if (sceneCount == 0) {
            reasons.add("no_scenes");
        }
        if (sceneCount > 0 && frameCount == 0) {
            reasons.add("no_representative_frames");
        }
        if (frameCount > 0 && sceneCount > Math.max(frameCount + 8, (int) Math.ceil(frameCount * 1.35))) {
            reasons.add("scene_count_exceeds_visual_evidence");
        }
        Map<String, Object> critic = sceneCritic == null ? new LinkedHashMap<>() : mutableMap(sceneCritic.sceneCritic());
        String status = defaultString(firstNonEmpty(critic.get("status"), sceneCritic == null ? null : sceneCritic.metadata().get("status")), "WARN").toUpperCase(Locale.ROOT);
        double confidence = doubleValue(critic.get("confidence"), 1.0);
        int coverage = intValue(critic.get("sceneCoverageScore"), 100);
        int readiness = intValue(critic.get("shortReadinessScore"), 100);
        int continuity = intValue(critic.get("visualContinuityScore"), 100);
        if ("FAIL".equals(status)) {
            reasons.add("scene_critic_failed");
        }
        if (confidence < 0.45) {
            reasons.add("low_scene_critic_confidence");
        }
        if (coverage < 45) {
            reasons.add("low_scene_coverage");
        }
        if (readiness < 45) {
            reasons.add("low_short_readiness");
        }
        if (continuity < 40) {
            reasons.add("low_visual_continuity");
        }
        String issueText = (defaultString(critic.get("summary"), "") + " " + defaultString(critic.get("globalIssues"), "")).toLowerCase(Locale.ROOT);
        for (String signal : List.of("insufficient visual evidence", "audit impossible", "thematic discontinu", "black screen", "static black", "misaligned", "hallucinat")) {
            if (issueText.contains(signal)) {
                reasons.add("critic_signal_" + signal.replace(' ', '_'));
            }
        }
        return reasons.stream().distinct().toList();
    }

    private boolean isTranscriptFirstSceneStrategy(
            ShortSceneAnalysisService.SceneAnalysisResult sceneAnalysis,
            GoogleShortSceneCriticService.SceneCriticResult sceneCritic
    ) {
        Map<String, Object> sceneMetadata = sceneAnalysis == null ? Map.of() : mapValue(sceneAnalysis.metadata());
        Map<String, Object> strategy = mapValue(sceneMetadata.get("pipelineStrategy"));
        String mode = defaultString(firstNonEmpty(
                strategy.get("mode"),
                sceneMetadata.get("evidenceMode"),
                sceneMetadata.get("source")
        ), "").toUpperCase(Locale.ROOT);
        Map<String, Object> critic = sceneCritic == null ? Map.of() : mapValue(sceneCritic.sceneCritic());
        String criticMode = defaultString(firstNonEmpty(
                critic.get("visualEvidenceMode"),
                mapValue(sceneCritic == null ? null : sceneCritic.metadata()).get("source")
        ), "").toUpperCase(Locale.ROOT);
        return mode.contains("TRANSCRIPT_QA_FAST_LANE")
                || mode.contains("TRANSCRIPT_FIRST_QA")
                || mode.contains("QUICK_TRANSCRIPT_TIMELINE")
                || mode.contains("TRANSCRIPT_TIMELINE_QUICK")
                || mode.contains("TRANSCRIPT_TIMELINE_FAST")
                || mode.contains("TRANSCRIPT_QA_FAST")
                || criticMode.contains("TRANSCRIPT_FIRST_QA")
                || criticMode.contains("TRANSCRIPT_TIMELINE_QUICK")
                || criticMode.contains("TRANSCRIPT_TIMELINE_FAST");
    }

    private GoogleShortSceneCriticService.SceneCriticResult withSceneEvidenceRepair(
            GoogleShortSceneCriticService.SceneCriticResult original,
            SceneEvidenceRepair repair
    ) {
        Map<String, Object> critic = original == null ? new LinkedHashMap<>() : mutableMap(original.sceneCritic());
        critic.put("status", "WARN");
        critic.put("fallbackApplied", true);
        critic.put("visualEvidenceMode", "TRANSCRIPT_LED_REVIEW_REQUIRED");
        critic.put("summary", "Scene critic found unsafe visual evidence; conservative transcript-led scene windows are being used and human visual review is required.");
        List<String> globalIssues = listOfStrings(critic.get("globalIssues"));
        globalIssues.add("Scene evidence repair applied: " + repair.metadata().get("reasons"));
        critic.put("globalIssues", globalIssues.stream().distinct().toList());
        Map<String, Object> guidance = mapValue(critic.get("compressionGuidance"));
        guidance.put("captionSafetyNotes", List.of("Use transcript-led captions and require visual review because scene evidence was repaired."));
        guidance.put("backgroundContinuityNotes", List.of("Do not infer visual continuity from the original scene map; repaired windows are conservative."));
        critic.put("compressionGuidance", guidance);

        Map<String, Object> metadata = original == null ? new LinkedHashMap<>() : mutableMap(original.metadata());
        metadata.put("status", "WARN");
        metadata.put("repair", repair.metadata());
        metadata.put("sceneCount", repair.sceneAnalysis().scenes().size());
        metadata.put("frameCount", repair.sceneAnalysis().frames().size());
        metadata.put("critic", critic);

        return new GoogleShortSceneCriticService.SceneCriticResult(
                original != null && original.mediaBacked(),
                original == null ? "deterministic" : original.provider(),
                original == null ? "scene-evidence-repair" : original.model(),
                critic,
                original == null ? List.of() : original.trace(),
                original == null ? List.of() : original.evidenceMetadata(),
                original == null ? Map.of() : original.tokenMetadata(),
                original == null ? Map.of() : original.costMetadata(),
                metadata
        );
    }

    private StoryEvidenceRepair repairStoryEvidenceIfWeak(
            CreatorShortVideo video,
            List<Map<String, Object>> transcript,
            Map<String, Object> graph,
            GoogleShortStoryCriticService.StoryCriticResult storyCritic
    ) {
        List<String> reasons = storyEvidenceRiskReasons(storyCritic);
        if (reasons.isEmpty()) {
            return StoryEvidenceRepair.notApplied();
        }
        List<Map<String, Object>> chains = deterministicStoryChains(transcript, graph);
        List<String> goodHookNodes = highInterestingnessNodeIds(transcript, 12);
        Map<String, Object> metadata = new LinkedHashMap<>();
        Map<String, Object> storyUnderstanding = storyCritic == null ? Map.of() : storyCritic.storyUnderstanding();
        Map<String, Object> critic = storyCritic == null ? Map.of() : storyCritic.storyCritic();
        metadata.put("source", "deterministic_story_confidence_repair");
        metadata.put("applied", true);
        metadata.put("mode", "GRAPH_BACKED_CHAIN_PRESERVATION");
        metadata.put("reasons", reasons);
        metadata.put("beatCount", listOfMaps(storyUnderstanding.get("beatMap")).size());
        metadata.put("originalChainCount", listOfMaps(storyUnderstanding.get("mustPreserveChains")).size());
        metadata.put("repairChainCount", chains.size());
        metadata.put("storyConfidence", doubleValue(critic.get("confidence"), 0.0));
        metadata.put("goodHookNodeCount", goodHookNodes.size());
        metadata.put("videoId", video == null || video.getId() == null ? "" : video.getId().toString());
        return new StoryEvidenceRepair(true, chains, goodHookNodes, metadata);
    }

    private List<String> storyEvidenceRiskReasons(GoogleShortStoryCriticService.StoryCriticResult storyCritic) {
        if (storyCritic == null) {
            return List.of("missing_story_critic");
        }
        Map<String, Object> storyUnderstanding = storyCritic.storyUnderstanding();
        Map<String, Object> critic = storyCritic.storyCritic();
        List<String> reasons = new ArrayList<>();
        String status = defaultString(critic.get("status"), "WARN").toUpperCase(Locale.ROOT);
        double confidence = doubleValue(critic.get("confidence"), 1.0);
        int beatCount = listOfMaps(storyUnderstanding.get("beatMap")).size();
        int chainCount = listOfMaps(storyUnderstanding.get("mustPreserveChains")).size();
        int contextRisk = intValue(critic.get("contextRiskScore"), 0);
        int compressionSafety = intValue(critic.get("compressionSafetyScore"), 100);
        if ("FAIL".equals(status)) {
            reasons.add("story_critic_failed");
        }
        if (confidence < 0.5) {
            reasons.add("low_story_confidence");
        }
        if (beatCount >= 40 && chainCount < Math.max(3, beatCount / 20)) {
            reasons.add("sparse_preservation_chains_for_beat_count");
        }
        if (contextRisk >= 65) {
            reasons.add("high_context_risk");
        }
        if (compressionSafety < 45) {
            reasons.add("low_compression_safety");
        }
        if (!listOfMaps(critic.get("brokenChains")).isEmpty()) {
            reasons.add("broken_story_chains");
        }
        if (!listOfMaps(critic.get("missingContext")).isEmpty()) {
            reasons.add("missing_story_context");
        }
        return reasons.stream().distinct().toList();
    }

    private GoogleShortStoryCriticService.StoryCriticResult withStoryEvidenceRepair(
            GoogleShortStoryCriticService.StoryCriticResult original,
            StoryEvidenceRepair repair
    ) {
        Map<String, Object> storyUnderstanding = original == null ? new LinkedHashMap<>() : mutableMap(original.storyUnderstanding());
        List<Map<String, Object>> existingChains = listOfMaps(storyUnderstanding.get("mustPreserveChains"));
        List<Map<String, Object>> mergedChains = mergeStoryChains(existingChains, repair.chains());
        storyUnderstanding.put("mustPreserveChains", mergedChains);
        storyUnderstanding.put("storyRepair", repair.metadata());

        Map<String, Object> critic = original == null ? new LinkedHashMap<>() : mutableMap(original.storyCritic());
        String status = defaultString(critic.get("status"), "WARN");
        critic.put("status", "FAIL".equalsIgnoreCase(status) ? "FAIL" : "WARN");
        critic.put("fallbackApplied", true);
        critic.put("storyEvidenceMode", "GRAPH_BACKED_CHAIN_PRESERVATION");
        critic.put("summary", "Story critic confidence was weak; deterministic graph-backed preservation rules were added and human story review is required.");

        List<Map<String, Object>> preservationRules = listOfMaps(critic.get("preservationRules"));
        for (Map<String, Object> chain : repair.chains()) {
            preservationRules.add(Map.of(
                    "rule", "Preserve deterministic " + defaultString(chain.get("type"), "story") + " chain",
                    "nodeIds", listOfStrings(chain.get("nodeIds")),
                    "reason", defaultString(chain.get("reason"), "Graph-backed chain repair.")
            ));
        }
        critic.put("preservationRules", preservationRules.stream().limit(80).toList());

        List<Map<String, Object>> repairActions = listOfMaps(critic.get("repairActions"));
        repairActions.add(Map.of(
                "action", "protect_nodes",
                "nodeIds", repair.chains().stream().flatMap(chain -> listOfStrings(chain.get("nodeIds")).stream()).distinct().limit(80).toList(),
                "reason", "Story critic confidence was low; protect graph-backed chains during compression."
        ));
        critic.put("repairActions", repairActions.stream().limit(40).toList());

        Map<String, Object> guidance = mapValue(critic.get("candidateGuidance"));
        List<String> goodHooks = listOfStrings(guidance.get("goodHookNodes"));
        goodHooks.addAll(repair.goodHookNodes());
        guidance.put("goodHookNodes", goodHooks.stream().distinct().limit(24).toList());
        List<String> recommendedAngles = listOfStrings(guidance.get("recommendedStoryAngles"));
        recommendedAngles.add("Use self-contained graph-backed chains because global story confidence is low.");
        guidance.put("recommendedStoryAngles", recommendedAngles.stream().distinct().limit(12).toList());
        critic.put("candidateGuidance", guidance);
        critic.put("storyRepair", repair.metadata());

        Map<String, Object> metadata = original == null ? new LinkedHashMap<>() : mutableMap(original.metadata());
        metadata.put("status", critic.get("status"));
        metadata.put("repair", repair.metadata());
        metadata.put("storyUnderstanding", storyUnderstanding);
        metadata.put("storyCritic", critic);

        return new GoogleShortStoryCriticService.StoryCriticResult(
                original != null && original.mediaBacked(),
                original == null ? "deterministic" : original.provider(),
                original == null ? "story-confidence-repair" : original.model(),
                storyUnderstanding,
                critic,
                original == null ? List.of() : original.trace(),
                original == null ? Map.of() : original.tokenMetadata(),
                original == null ? Map.of() : original.costMetadata(),
                metadata
        );
    }

    private List<Map<String, Object>> deterministicStoryChains(List<Map<String, Object>> transcript, Map<String, Object> graph) {
        Set<String> protectedTypes = Set.of("QUESTION_ANSWER", "PROBLEM_SOLUTION", "CAUSE_EFFECT", "SETUP_PAYOFF", "ESCALATION", "CONTRAST", "REVEAL");
        Set<String> transcriptIds = transcript == null
                ? Set.of()
                : transcript.stream().map(node -> defaultString(node.get("id"), "")).filter(id -> !id.isBlank()).collect(java.util.stream.Collectors.toSet());
        List<Map<String, Object>> chains = new ArrayList<>();
        int index = 1;
        for (Map<String, Object> edge : listOfMaps(graph == null ? null : graph.get("edges"))) {
            String type = defaultString(edge.get("type"), "").toUpperCase(Locale.ROOT);
            String from = defaultString(edge.get("from"), "");
            String to = defaultString(edge.get("to"), "");
            if (!protectedTypes.contains(type) || from.isBlank() || to.isBlank()) {
                continue;
            }
            if ((!transcriptIds.isEmpty() && (!transcriptIds.contains(from) || !transcriptIds.contains(to)))) {
                continue;
            }
            Map<String, Object> chain = new LinkedHashMap<>();
            chain.put("chainId", "story-repair-chain-%03d".formatted(index++));
            chain.put("type", type.toLowerCase(Locale.ROOT));
            chain.put("nodeIds", List.of(from, to));
            chain.put("reason", defaultString(edge.get("reason"), "Deterministic graph edge should be preserved during compression."));
            chain.put("source", "deterministic_story_confidence_repair");
            chains.add(chain);
            if (chains.size() >= 32) {
                break;
            }
        }
        if (chains.size() < 6) {
            chains.addAll(localContextChains(transcript, index, 12 - chains.size()));
        }
        return mergeStoryChains(List.of(), chains).stream().limit(32).toList();
    }

    private List<Map<String, Object>> localContextChains(List<Map<String, Object>> transcript, int startIndex, int limit) {
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (Map<String, Object> node : transcript == null ? List.<Map<String, Object>>of() : transcript) {
            nodes.add(node == null ? new LinkedHashMap<>() : new LinkedHashMap<>(node));
        }
        nodes.sort((left, right) -> Integer.compare(intValue(right.get("interestingness"), 0), intValue(left.get("interestingness"), 0)));
        if (nodes.size() > Math.max(0, limit)) {
            nodes = new ArrayList<>(nodes.subList(0, Math.max(0, limit)));
        }
        Map<String, Integer> ordinalById = new LinkedHashMap<>();
        List<Map<String, Object>> ordered = transcript == null ? List.of() : transcript;
        for (int index = 0; index < ordered.size(); index++) {
            String id = defaultString(ordered.get(index).get("id"), "");
            if (!id.isBlank()) {
                ordinalById.put(id, index);
            }
        }
        List<Map<String, Object>> chains = new ArrayList<>();
        int chainIndex = startIndex;
        for (Map<String, Object> node : nodes) {
            String id = defaultString(node.get("id"), "");
            Integer ordinal = ordinalById.get(id);
            if (ordinal == null) {
                continue;
            }
            List<String> nodeIds = new ArrayList<>();
            if (ordinal > 0) {
                nodeIds.add(defaultString(ordered.get(ordinal - 1).get("id"), ""));
            }
            nodeIds.add(id);
            if (ordinal + 1 < ordered.size()) {
                nodeIds.add(defaultString(ordered.get(ordinal + 1).get("id"), ""));
            }
            nodeIds = nodeIds.stream().filter(value -> !value.isBlank()).distinct().toList();
            if (nodeIds.size() < 2) {
                continue;
            }
            Map<String, Object> chain = new LinkedHashMap<>();
            chain.put("chainId", "story-repair-chain-%03d".formatted(chainIndex++));
            chain.put("type", "local_context");
            chain.put("nodeIds", nodeIds);
            chain.put("reason", "Protect local context around a high-interest beat because global story confidence is low.");
            chain.put("source", "deterministic_story_confidence_repair");
            chains.add(chain);
        }
        return chains;
    }

    private List<Map<String, Object>> mergeStoryChains(List<Map<String, Object>> existing, List<Map<String, Object>> repair) {
        Map<String, Map<String, Object>> byKey = new LinkedHashMap<>();
        for (Map<String, Object> chain : existing == null ? List.<Map<String, Object>>of() : existing) {
            List<String> ids = listOfStrings(chain.get("nodeIds"));
            if (!ids.isEmpty()) {
                byKey.put(String.join(">", ids), new LinkedHashMap<>(chain));
            }
        }
        for (Map<String, Object> chain : repair == null ? List.<Map<String, Object>>of() : repair) {
            List<String> ids = listOfStrings(chain.get("nodeIds"));
            if (!ids.isEmpty()) {
                byKey.putIfAbsent(String.join(">", ids), new LinkedHashMap<>(chain));
            }
        }
        return new ArrayList<>(byKey.values());
    }

    private List<String> highInterestingnessNodeIds(List<Map<String, Object>> transcript, int limit) {
        return (transcript == null ? List.<Map<String, Object>>of() : transcript).stream()
                .sorted((left, right) -> Integer.compare(intValue(right.get("interestingness"), 0), intValue(left.get("interestingness"), 0)))
                .map(node -> defaultString(node.get("id"), ""))
                .filter(id -> !id.isBlank())
                .distinct()
                .limit(limit)
                .toList();
    }

    private List<Map<String, Object>> conservativeScenes(CreatorShortVideo video, List<Map<String, Object>> transcript, List<Map<String, Object>> frames) {
        double duration = Math.max(transcriptDurationSeconds(transcript), maxFrameTimestamp(frames));
        if (duration <= 0) {
            duration = video == null || video.getTargetDurationSeconds() == null ? 60.0 : Math.max(30.0, video.getTargetDurationSeconds());
        }
        double windowSeconds = duration <= 120 ? 15.0 : 30.0;
        int maxScenes = Math.max(1, Math.min(24, (int) Math.ceil(duration / windowSeconds)));
        double actualWindow = duration / maxScenes;
        List<Map<String, Object>> scenes = new ArrayList<>();
        for (int index = 0; index < maxScenes; index++) {
            double start = roundSeconds(index * actualWindow);
            double end = roundSeconds(index == maxScenes - 1 ? duration : Math.max(start + 0.5, (index + 1) * actualWindow));
            Map<String, Object> scene = new LinkedHashMap<>();
            scene.put("id", "scene-%03d".formatted(index + 1));
            scene.put("index", index + 1);
            scene.put("start", start);
            scene.put("end", end);
            scene.put("durationSeconds", roundSeconds(end - start));
            scene.put("label", "Conservative window " + (index + 1));
            scene.put("source", "conservative_transcript_window");
            scene.put("evidenceMode", "TRANSCRIPT_LED_REVIEW_REQUIRED");
            scene.put("representativeTimestamp", roundSeconds(start + Math.max(0.0, end - start) / 2.0));
            scene.put("frameIds", new ArrayList<String>());
            scenes.add(scene);
        }
        return scenes;
    }

    private List<Map<String, Object>> reassignFramesToConservativeScenes(List<Map<String, Object>> frames, List<Map<String, Object>> transcript) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> frame : frames == null ? List.<Map<String, Object>>of() : frames) {
            result.add(frame == null ? new LinkedHashMap<>() : new LinkedHashMap<>(frame));
        }
        return result;
    }

    private List<Map<String, Object>> reassignFramesToScenes(List<Map<String, Object>> frames, List<Map<String, Object>> scenes) {
        List<Map<String, Object>> reassigned = new ArrayList<>();
        for (Map<String, Object> frame : frames == null ? List.<Map<String, Object>>of() : frames) {
            Map<String, Object> copy = frame == null ? new LinkedHashMap<>() : new LinkedHashMap<>(frame);
            Map<String, Object> scene = sceneAtTimestamp(scenes, doubleValue(copy.get("timestampSeconds"), -1.0));
            if (!scene.isEmpty()) {
                copy.put("sceneId", scene.get("id"));
            }
            reassigned.add(copy);
        }
        return reassigned;
    }

    private List<Map<String, Object>> attachFrameIdsToScenes(List<Map<String, Object>> scenes, List<Map<String, Object>> frames) {
        List<Map<String, Object>> repaired = new ArrayList<>();
        for (Map<String, Object> scene : scenes == null ? List.<Map<String, Object>>of() : scenes) {
            Map<String, Object> copy = new LinkedHashMap<>(scene);
            List<String> ids = frameIdsForWindow(frames, doubleValue(copy.get("start"), 0.0), doubleValue(copy.get("end"), 0.0));
            if (ids.isEmpty()) {
                String nearest = nearestFrameId(frames, doubleValue(copy.get("representativeTimestamp"), doubleValue(copy.get("start"), 0.0)));
                if (!nearest.isBlank()) {
                    ids = List.of(nearest);
                }
            }
            copy.put("frameIds", ids);
            if (!ids.isEmpty()) {
                copy.put("representativeFrameId", ids.get(0));
            }
            repaired.add(copy);
        }
        return repaired;
    }

    private List<Map<String, Object>> attachScenesAndFramesToTranscript(List<Map<String, Object>> transcript, List<Map<String, Object>> scenes, List<Map<String, Object>> frames) {
        List<Map<String, Object>> attached = new ArrayList<>();
        for (Map<String, Object> node : transcript == null ? List.<Map<String, Object>>of() : transcript) {
            Map<String, Object> copy = node == null ? new LinkedHashMap<>() : new LinkedHashMap<>(node);
            double start = doubleValue(copy.get("start"), 0.0);
            double end = doubleValue(copy.get("end"), start);
            Map<String, Object> scene = sceneAtTimestamp(scenes, start + Math.max(0.0, end - start) / 2.0);
            if (!scene.isEmpty()) {
                copy.put("sceneId", scene.get("id"));
                copy.put("sceneStart", scene.get("start"));
                copy.put("sceneEnd", scene.get("end"));
            }
            copy.put("frames", frameIdsForWindow(frames, start, end));
            attached.add(copy);
        }
        return attached;
    }

    private Map<String, Object> sceneAtTimestamp(List<Map<String, Object>> scenes, double timestamp) {
        for (Map<String, Object> scene : scenes == null ? List.<Map<String, Object>>of() : scenes) {
            double start = doubleValue(scene.get("start"), 0.0);
            double end = doubleValue(scene.get("end"), start);
            if (timestamp >= start && timestamp <= end) {
                return scene;
            }
        }
        return scenes == null || scenes.isEmpty() ? new LinkedHashMap<>() : scenes.get(scenes.size() - 1);
    }

    private List<String> frameIdsForWindow(List<Map<String, Object>> frames, double start, double end) {
        List<String> ids = new ArrayList<>();
        for (Map<String, Object> frame : frames == null ? List.<Map<String, Object>>of() : frames) {
            double timestamp = doubleValue(frame.get("timestampSeconds"), -1.0);
            String id = defaultString(frame.get("id"), "");
            if (!id.isBlank() && timestamp >= start && timestamp <= end) {
                ids.add(id);
            }
        }
        return ids.stream().distinct().toList();
    }

    private String nearestFrameId(List<Map<String, Object>> frames, double timestamp) {
        String id = "";
        double distance = Double.MAX_VALUE;
        for (Map<String, Object> frame : frames == null ? List.<Map<String, Object>>of() : frames) {
            double frameTime = doubleValue(frame.get("timestampSeconds"), -1.0);
            String frameId = defaultString(frame.get("id"), "");
            if (frameTime < 0 || frameId.isBlank()) {
                continue;
            }
            double candidateDistance = Math.abs(frameTime - timestamp);
            if (candidateDistance < distance) {
                distance = candidateDistance;
                id = frameId;
            }
        }
        return id;
    }

    private double transcriptDurationSeconds(List<Map<String, Object>> transcript) {
        double duration = 0.0;
        for (Map<String, Object> node : transcript == null ? List.<Map<String, Object>>of() : transcript) {
            duration = Math.max(duration, doubleValue(node.get("end"), 0.0));
        }
        return duration;
    }

    private double maxFrameTimestamp(List<Map<String, Object>> frames) {
        double timestamp = 0.0;
        for (Map<String, Object> frame : frames == null ? List.<Map<String, Object>>of() : frames) {
            timestamp = Math.max(timestamp, doubleValue(frame.get("timestampSeconds"), 0.0));
        }
        return timestamp;
    }

    private boolean shouldStartEarlySceneVisualPrep(Map<String, Object> videoDna) {
        String primaryType = normalizedType(videoDna == null ? null : videoDna.get("primaryType"));
        String structureType = normalizedType(videoDna == null ? null : videoDna.get("structureType"));
        if ("qa".equals(structureType) || "interview".equals(primaryType)) {
            return false;
        }
        return isVisualFirstContent(primaryType, structureType, dnaSignalText(videoDna));
    }

    private boolean isVisualFirstContent(String primaryType, String structureType, String signal) {
        String normalizedSignal = defaultString(signal, "").toLowerCase(Locale.ROOT);
        if (List.of("montage", "music_video", "gameplay", "sports", "dance", "cinematic", "animation").contains(primaryType)
                || List.of("montage", "music_video", "visual_montage", "gameplay", "silent").contains(structureType)) {
            return true;
        }
        return normalizedSignal.contains("music video")
                || normalizedSignal.contains("visual montage")
                || normalizedSignal.contains("mostly visual")
                || normalizedSignal.contains("little narration")
                || normalizedSignal.contains("silent")
                || normalizedSignal.contains("gameplay")
                || normalizedSignal.contains("sports");
    }

    private ShortsPipelineStrategy frameFreeVideoAnalysisStrategy(
            CreatorShortVideo video,
            String mode,
            long sourceSizeBytes,
            int transcriptNodeCount
    ) {
        String safeMode = defaultString(mode, "VIDEO_ANALYSIS");
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "frame_free_video_analysis_strategy");
        metadata.put("mode", safeMode);
        metadata.put("summary", "Full-video Gemini scene analysis runs sequentially; frame extraction and frame-image critic are skipped.");
        metadata.put("reason", "rate_limit_and_pod_memory_safe_video_analysis");
        metadata.put("fullVideoAnalysisIncluded", true);
        metadata.put("videoAnalysisMode", "sequential_gemini_full_video_scene_map");
        metadata.put("videoAnalysisChunkSeconds", 600);
        metadata.put("parallelProviderCalls", false);
        metadata.put("frameExtractionSkipped", true);
        metadata.put("skipSceneVisualPrep", false);
        metadata.put("skipDeepSceneCritic", true);
        metadata.put("visualContinuityScenes", true);
        metadata.put("sourceSizeBytes", sourceSizeBytes);
        metadata.put("transcriptNodeCount", transcriptNodeCount);
        metadata.put("videoId", video == null || video.getId() == null ? "" : video.getId().toString());
        metadata.put("selectedAt", OffsetDateTime.now().toString());
        return new ShortsPipelineStrategy(
                safeMode,
                true,
                false,
                false,
                true,
                false,
                true,
                "sequential_gemini_full_video_scene_map",
                "Video analysis running full source video through Gemini scene-map windows",
                "Scene critic reusing full-video scene map without frame-image audit",
                metadata
        );
    }

    private ShortsPipelineStrategy determineShortsPipelineStrategy(
            CreatorShortVideo video,
            Map<String, Object> upstreamVideoDna,
            Map<String, Object> criticVideoDna,
            List<Map<String, Object>> transcript,
            long sourceSizeBytes
    ) {
        Map<String, Object> dna = criticVideoDna == null || criticVideoDna.isEmpty()
                ? mutableMap(upstreamVideoDna)
                : mutableMap(criticVideoDna);
        double durationSeconds = transcriptDurationSeconds(transcript);
        TranscriptQaSignal qaSignal = transcriptQaSignal(transcript);
        String primaryType = normalizedType(dna.get("primaryType"));
        String structureType = normalizedType(dna.get("structureType"));
        boolean qaTyped = "qa".equals(structureType) || "interview".equals(primaryType);
        boolean podcastTyped = "podcast".equals(primaryType) || "interview".equals(primaryType) || dnaSignalText(dna).contains("podcast");
        boolean transcriptQa = qaSignal.questionCount() >= 2 || qaSignal.questionRatio() >= 0.08;
        boolean preferQuestionAnswerCuts = qaTyped || (podcastTyped && transcriptQa);
        boolean transcriptRich = transcript != null && transcript.size() >= 6 && durationSeconds >= 30.0;
        boolean visualFirstContent = isVisualFirstContent(primaryType, structureType, dnaSignalText(dna));
        boolean transcriptFirst = preferQuestionAnswerCuts || (transcriptRich && !visualFirstContent);
        boolean fastDuration = durationSeconds <= 0 || durationSeconds <= FAST_LANE_MAX_DURATION_SECONDS;
        boolean visualContinuity = !transcriptFirst && (fastDuration || shouldStartEarlySceneVisualPrep(dna));

        String mode;
        String summary;
        String reason;
        if (preferQuestionAnswerCuts) {
            mode = "TRANSCRIPT_QA_FAST_LANE";
            reason = qaTyped
                    ? "video_type_critic_confirmed_question_answer_structure"
                    : "podcast_or_interview_with_question_answer_transcript_signal";
            summary = "Transcript-aware Q&A strategy selected; full-video Gemini scene analysis will run sequentially and the frame-image critic will be skipped.";
        } else if (transcriptFirst) {
            mode = "QUICK_TRANSCRIPT_TIMELINE";
            reason = "transcript_rich_quick_baseline_with_optional_visual_analysis";
            summary = "Quick transcript timeline selected with full-video Gemini scene analysis in the main pipeline.";
        } else if (visualContinuity) {
            mode = "VISUAL_CONTINUITY_FAST_LANE";
            reason = fastDuration
                    ? "video_duration_within_20_minute_visual_continuity_budget"
                    : "video_type_requires_visual_continuity";
            summary = "Visual continuity strategy selected; scene analysis will use full-video Gemini windows instead of frame extraction.";
        } else {
            mode = "STANDARD_LONG_VIDEO_SPARSE_VISUALS";
            reason = "long_or_unknown_video_uses_sparse_visual_windows";
            summary = "Standard long-video strategy selected; scene analysis will use sequential 10-minute Gemini video windows.";
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "deterministic_pipeline_strategy_selector");
        metadata.put("mode", mode);
        metadata.put("summary", summary);
        metadata.put("reason", reason);
        metadata.put("targetCompletionMinutes", 10);
        metadata.put("durationSeconds", roundSeconds(durationSeconds));
        metadata.put("fastLaneMaxDurationSeconds", roundSeconds(FAST_LANE_MAX_DURATION_SECONDS));
        metadata.put("sourceSizeBytes", sourceSizeBytes);
        metadata.put("primaryType", primaryType);
        metadata.put("structureType", structureType);
        metadata.put("questionCount", qaSignal.questionCount());
        metadata.put("questionRatio", roundSeconds(qaSignal.questionRatio()));
        metadata.put("speakerTurnCount", qaSignal.speakerTurnCount());
        metadata.put("transcriptNodeCount", transcript == null ? 0 : transcript.size());
        metadata.put("transcriptRich", transcriptRich);
        metadata.put("visualFirstContent", visualFirstContent);
        metadata.put("transcriptFirstScenes", false);
        metadata.put("fullVideoAnalysisIncluded", true);
        metadata.put("videoAnalysisMode", "sequential_gemini_full_video_scene_map");
        metadata.put("videoAnalysisChunkSeconds", 600);
        metadata.put("parallelProviderCalls", false);
        metadata.put("skipSceneVisualPrep", false);
        metadata.put("skipDeepSceneCritic", true);
        metadata.put("visualContinuityScenes", true);
        metadata.put("questionAnswerCutsPreferred", preferQuestionAnswerCuts);
        metadata.put("quickPublishFirst", false);
        metadata.put("optionalVisualAnalysisRecommended", false);
        metadata.put("visualAnalysisLabel", "Full video analysis");
        metadata.put("videoId", video == null || video.getId() == null ? "" : video.getId().toString());
        metadata.put("selectedAt", OffsetDateTime.now().toString());

        return new ShortsPipelineStrategy(
                mode,
                transcriptFirst || visualContinuity,
                false,
                false,
                true,
                preferQuestionAnswerCuts,
                true,
                "sequential_gemini_full_video_scene_map",
                "Video analysis running full source video through Gemini scene-map windows",
                "Scene critic reusing full-video scene map without frame-image audit",
                metadata
        );
    }

    private ShortSceneAnalysisService.SceneAnalysisResult transcriptFirstSceneAnalysis(
            CreatorShortVideo video,
            List<Map<String, Object>> transcript,
            ShortsPipelineStrategy strategy
    ) {
        boolean qaMode = strategy.preferQuestionAnswerCuts();
        String source = qaMode ? "transcript_qa_fast_lane" : "transcript_timeline_fast_lane";
        String evidenceMode = qaMode ? "TRANSCRIPT_FIRST_QA" : "TRANSCRIPT_TIMELINE_QUICK";
        String boundaryMode = qaMode ? "question_answer_transcript_boundaries" : "transcript_timeline_boundaries";
        String emptySummary = qaMode
                ? "Transcript-first scene analysis could not create Q&A windows."
                : "Quick transcript timeline analysis could not create usable scene windows.";
        String successSummary = qaMode
                ? "Transcript-first Q&A scene windows were generated without visual scene scan."
                : "Quick transcript timeline scene windows were generated without visual scene scan.";
        List<List<Map<String, Object>>> groups = transcriptSceneGroups(transcript, strategy.preferQuestionAnswerCuts());
        List<Map<String, Object>> scenes = scenesFromTranscriptGroups(groups, strategy);
        List<Map<String, Object>> attachedTranscript = attachTranscriptScenes(transcript, scenes);
        List<Map<String, Object>> trace = List.of(traceRow(
                "SCENE_ANALYSIS",
                scenes.isEmpty() ? "WARN" : "COMPLETED",
                scenes.isEmpty() ? emptySummary : successSummary,
                scenes.isEmpty() ? 0.35 : 0.86,
                Map.of(
                        "source", source,
                        "sceneCount", scenes.size(),
                        "frameCount", 0,
                        "transcriptNodeCount", attachedTranscript.size(),
                        "evidenceMode", evidenceMode,
                        "pipelineStrategy", strategy.mode()
                )
        ));
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", source);
        metadata.put("status", scenes.isEmpty() ? "WARN" : "COMPLETED");
        metadata.put("mediaBacked", false);
        metadata.put("evidenceMode", evidenceMode);
        metadata.put("sceneBoundaryMode", boundaryMode);
        metadata.put("visualPrepSkipped", true);
        metadata.put("visualPrepSkipReason", strategy.mode());
        metadata.put("sceneCount", scenes.size());
        metadata.put("frameCount", 0);
        metadata.put("transcriptNodeCount", attachedTranscript.size());
        metadata.put("durationSeconds", roundSeconds(transcriptDurationSeconds(attachedTranscript)));
        metadata.put("pipelineStrategy", strategy.metadata());
        metadata.put("generatedAt", OffsetDateTime.now().toString());
        log.info(
                "Shorts transcript-first scene analysis complete jobId={} videoId={} scenes={} transcriptNodes={} mode={}",
                video == null ? null : video.getGenerationJobId(),
                video == null ? null : video.getId(),
                scenes.size(),
                attachedTranscript.size(),
                strategy.mode()
        );
        return new ShortSceneAnalysisService.SceneAnalysisResult(false, scenes, List.of(), attachedTranscript, trace, metadata);
    }

    private GoogleShortSceneCriticService.SceneCriticResult transcriptFirstSceneCritic(
            CreatorShortVideo video,
            ShortSceneAnalysisService.SceneAnalysisResult sceneAnalysis,
            ShortsPipelineStrategy strategy
    ) {
        int sceneCount = sceneAnalysis == null ? 0 : sceneAnalysis.scenes().size();
        int transcriptNodeCount = sceneAnalysis == null ? 0 : sceneAnalysis.transcript().size();
        String status = sceneCount == 0 ? "WARN" : "PASS";
        double confidence = sceneCount == 0 ? 0.42 : 0.84;
        boolean qaMode = strategy.preferQuestionAnswerCuts();
        String source = qaMode ? "deterministic_transcript_qa_scene_critic" : "deterministic_transcript_timeline_scene_critic";
        String evidenceMode = qaMode ? "TRANSCRIPT_FIRST_QA" : "TRANSCRIPT_TIMELINE_QUICK";
        String successSummary = qaMode
                ? "Transcript-first scene critic validated Q&A timeline boundaries and skipped expensive visual audit by strategy."
                : "Transcript-first scene critic validated source transcript timeline boundaries and left deeper visual evidence for optional analysis.";

        Map<String, Object> guidance = new LinkedHashMap<>();
        guidance.put("avoidCuts", qaMode
                ? List.of("Do not cut before the answer resolves the preceding question.")
                : List.of("Do not cut mid-sentence; prefer complete transcript thoughts with clean caption timing."));
        guidance.put("preferredCutPoints", preferredTranscriptCutPoints(sceneAnalysis == null ? List.of() : sceneAnalysis.scenes(), qaMode));
        guidance.put("brollOpportunities", List.of("Optional b-roll can be added after candidate selection; transcript timing remains the source of truth."));
        guidance.put("captionSafetyNotes", List.of("Use transcript nodes as caption source; no OCR-derived captions are required for this mode."));
        guidance.put("backgroundContinuityNotes", List.of("Quick transcript timeline assumes visuals are acceptable for first review; optional visual analysis can enrich scene evidence later."));

        Map<String, Object> critic = new LinkedHashMap<>();
        critic.put("source", source);
        critic.put("status", status);
        critic.put("confidence", confidence);
        critic.put("summary", sceneCount == 0
                ? "Transcript-first scene critic could not validate scene windows because none were generated."
                : successSummary);
        critic.put("globalIssues", sceneCount == 0 ? List.of("No transcript-first scene windows were generated.") : List.of());
        critic.put("sceneCoverageScore", sceneCount == 0 ? 0 : 92);
        critic.put("boundaryAccuracyScore", sceneCount == 0 ? 0 : 86);
        critic.put("visualContinuityScore", sceneCount == 0 ? 0 : 72);
        critic.put("backgroundConsistencyScore", sceneCount == 0 ? 0 : 72);
        critic.put("transcriptAlignmentScore", sceneCount == 0 ? 0 : 96);
        critic.put("shortReadinessScore", sceneCount == 0 ? 0 : 88);
        critic.put("visualEvidenceMode", evidenceMode);
        critic.put("compressionGuidance", guidance);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", source);
        metadata.put("status", status);
        metadata.put("generatedAt", OffsetDateTime.now().toString());
        metadata.put("sceneCount", sceneCount);
        metadata.put("frameCount", 0);
        metadata.put("transcriptNodeCount", transcriptNodeCount);
        metadata.put("mediaBacked", false);
        metadata.put("pipelineStrategy", strategy.metadata());
        metadata.put("critic", critic);
        metadata.put("videoId", video == null || video.getId() == null ? "" : video.getId().toString());

        Map<String, Object> tokenMetadata = new LinkedHashMap<>();
        tokenMetadata.put("inputTokens", 0);
        tokenMetadata.put("outputTokens", 0);
        tokenMetadata.put("totalTokens", 0);
        tokenMetadata.put("source", "NONE");
        Map<String, Object> costMetadata = new LinkedHashMap<>();
        costMetadata.put("provider", "deterministic");
        costMetadata.put("model", qaMode ? "transcript-qa-scene-critic" : "transcript-timeline-scene-critic");
        costMetadata.put("operation", "SHORTS_SCENE_CRITIC");
        costMetadata.put("estimatedCost", 0);

        List<Map<String, Object>> trace = List.of(traceRow(
                "SCENE_CRITIC",
                status,
                stringValue(critic.get("summary")),
                confidence,
                Map.of(
                        "source", source,
                        "evidenceMode", evidenceMode,
                        "sceneCount", sceneCount,
                        "frameCount", 0,
                        "pipelineStrategy", strategy.mode()
                )
        ));
        return new GoogleShortSceneCriticService.SceneCriticResult(
                false,
                "deterministic",
                qaMode ? "transcript-qa-scene-critic" : "transcript-timeline-scene-critic",
                critic,
                trace,
                List.of(),
                tokenMetadata,
                costMetadata,
                metadata
        );
    }

    private GoogleShortSceneCriticService.SceneCriticResult sceneMapBackedSceneCritic(
            CreatorShortVideo video,
            ShortSceneAnalysisService.SceneAnalysisResult sceneAnalysis,
            ShortsPipelineStrategy strategy
    ) {
        int sceneCount = sceneAnalysis == null ? 0 : sceneAnalysis.scenes().size();
        int transcriptNodeCount = sceneAnalysis == null ? 0 : sceneAnalysis.transcript().size();
        Map<String, Object> sceneMetadata = sceneAnalysis == null ? Map.of() : mapValue(sceneAnalysis.metadata());
        Map<String, Object> sceneMapCost = mapValue(sceneMetadata.get("costMetadata"));
        String model = defaultString(firstNonEmpty(sceneMetadata.get("model"), sceneMapCost.get("model")), "gemini-video-scene-map");
        String status = sceneCount == 0 ? "WARN" : "PASS";
        double confidence = sceneCount == 0 ? 0.42 : 0.88;

        Map<String, Object> guidance = new LinkedHashMap<>();
        guidance.put("avoidCuts", List.of(
                "Do not cut across a Gemini scene boundary unless transcript context clearly resolves.",
                "Avoid trims in the first or last 0.5 seconds of a visual scene unless captions need it."
        ));
        guidance.put("preferredCutPoints", preferredTranscriptCutPoints(sceneAnalysis == null ? List.of() : sceneAnalysis.scenes(), false));
        guidance.put("brollOpportunities", List.of("Use Gemini scene summaries and visual roles to choose B-roll or visual changes; no extra frame audit was run."));
        guidance.put("captionSafetyNotes", List.of("Use verified transcript nodes for captions and align them to Gemini scene windows."));
        guidance.put("backgroundContinuityNotes", List.of("Scene continuity comes from full-video Gemini analysis windows, not representative backend frames."));

        Map<String, Object> critic = new LinkedHashMap<>();
        critic.put("source", "gemini_video_scene_map_scene_critic");
        critic.put("status", status);
        critic.put("confidence", confidence);
        critic.put("summary", sceneCount == 0
                ? "Full-video Gemini scene map did not provide usable scenes."
                : "Full-video Gemini scene map is being reused as the visual evidence source; frame-by-frame scene critic was skipped.");
        critic.put("globalIssues", sceneCount == 0 ? List.of("No full-video scene map windows were available.") : List.of());
        critic.put("sceneCoverageScore", sceneCount == 0 ? 0 : 90);
        critic.put("boundaryAccuracyScore", sceneCount == 0 ? 0 : 84);
        critic.put("visualContinuityScore", sceneCount == 0 ? 0 : 82);
        critic.put("backgroundConsistencyScore", sceneCount == 0 ? 0 : 82);
        critic.put("transcriptAlignmentScore", sceneCount == 0 ? 0 : 90);
        critic.put("shortReadinessScore", sceneCount == 0 ? 0 : 88);
        critic.put("visualEvidenceMode", "GEMINI_FULL_VIDEO_SCENE_MAP");
        critic.put("frameByFrameCriticSkipped", true);
        critic.put("compressionGuidance", guidance);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "gemini_video_scene_map_scene_critic");
        metadata.put("status", status);
        metadata.put("generatedAt", OffsetDateTime.now().toString());
        metadata.put("sceneCount", sceneCount);
        metadata.put("frameCount", 0);
        metadata.put("transcriptNodeCount", transcriptNodeCount);
        metadata.put("mediaBacked", true);
        metadata.put("sceneMapMetadata", sceneMetadata);
        metadata.put("pipelineStrategy", strategy == null ? Map.of() : strategy.metadata());
        metadata.put("critic", critic);
        metadata.put("videoId", video == null || video.getId() == null ? "" : video.getId().toString());

        Map<String, Object> tokenMetadata = new LinkedHashMap<>();
        tokenMetadata.put("inputTokens", 0);
        tokenMetadata.put("outputTokens", 0);
        tokenMetadata.put("totalTokens", 0);
        tokenMetadata.put("source", "REUSED_GEMINI_VIDEO_SCENE_MAP");
        Map<String, Object> costMetadata = new LinkedHashMap<>();
        costMetadata.put("provider", "deterministic");
        costMetadata.put("model", "gemini-video-scene-map-reuse");
        costMetadata.put("operation", "SHORTS_SCENE_CRITIC_REUSED_VIDEO_ANALYSIS");
        costMetadata.put("estimatedCost", 0);
        costMetadata.put("source", "REUSED_GEMINI_VIDEO_SCENE_MAP");

        List<Map<String, Object>> trace = List.of(traceRow(
                "SCENE_CRITIC",
                status,
                stringValue(critic.get("summary")),
                confidence,
                Map.of(
                        "source", "gemini_video_scene_map_scene_critic",
                        "evidenceMode", "GEMINI_FULL_VIDEO_SCENE_MAP",
                        "sceneCount", sceneCount,
                        "frameCount", 0,
                        "frameByFrameCriticSkipped", true,
                        "pipelineStrategy", strategy == null ? "" : strategy.mode()
                )
        ));
        return new GoogleShortSceneCriticService.SceneCriticResult(
                true,
                "gemini",
                model,
                critic,
                trace,
                List.of(),
                tokenMetadata,
                costMetadata,
                metadata
        );
    }

    private List<Map<String, Object>> preferredTranscriptCutPoints(List<Map<String, Object>> scenes, boolean qaMode) {
        List<Map<String, Object>> points = new ArrayList<>();
        for (Map<String, Object> scene : scenes == null ? List.<Map<String, Object>>of() : scenes) {
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("sceneId", defaultString(scene.get("id"), ""));
            point.put("start", scene.get("start"));
            point.put("end", scene.get("end"));
            point.put("reason", qaMode ? "question_answer_boundary" : "complete_transcript_window");
            points.add(point);
            if (points.size() >= 40) {
                break;
            }
        }
        return points;
    }

    private List<List<Map<String, Object>>> transcriptSceneGroups(List<Map<String, Object>> transcript, boolean preferQuestionAnswerCuts) {
        List<Map<String, Object>> safe = new ArrayList<>();
        for (Map<String, Object> node : transcript == null ? List.<Map<String, Object>>of() : transcript) {
            if (node != null) {
                safe.add(new LinkedHashMap<>(node));
            }
        }
        safe.sort((left, right) -> Double.compare(
                doubleValue(firstNonEmpty(left.get("start"), left.get("sourceStart")), 0.0),
                doubleValue(firstNonEmpty(right.get("start"), right.get("sourceStart")), 0.0)
        ));
        if (safe.isEmpty()) {
            return List.of();
        }

        List<List<Map<String, Object>>> groups = new ArrayList<>();
        List<Map<String, Object>> current = new ArrayList<>();
        boolean currentStartedWithQuestion = false;
        for (Map<String, Object> node : safe) {
            boolean question = questionLike(nodeText(node));
            if (current.isEmpty()) {
                current.add(node);
                currentStartedWithQuestion = question;
                continue;
            }
            double start = nodeStartSeconds(current.get(0));
            double candidateEnd = nodeEndSeconds(node);
            double candidateDuration = candidateEnd - start;
            boolean boundaryAtQuestion = preferQuestionAnswerCuts
                    && question
                    && currentStartedWithQuestion
                    && candidateDuration >= TRANSCRIPT_QA_MIN_WINDOW_SECONDS;
            boolean boundaryAtMaxWindow = candidateDuration > TRANSCRIPT_QA_MAX_WINDOW_SECONDS;
            if (boundaryAtQuestion || boundaryAtMaxWindow) {
                groups.add(current);
                current = new ArrayList<>();
                current.add(node);
                currentStartedWithQuestion = question;
            } else {
                current.add(node);
                currentStartedWithQuestion = currentStartedWithQuestion || question;
            }
        }
        if (!current.isEmpty()) {
            groups.add(current);
        }
        return mergeTranscriptSceneGroups(groups, 180);
    }

    private List<List<Map<String, Object>>> mergeTranscriptSceneGroups(List<List<Map<String, Object>>> groups, int maxGroups) {
        List<List<Map<String, Object>>> merged = new ArrayList<>(groups == null ? List.of() : groups);
        while (merged.size() > maxGroups) {
            List<List<Map<String, Object>>> next = new ArrayList<>();
            for (int index = 0; index < merged.size(); index += 2) {
                List<Map<String, Object>> group = new ArrayList<>(merged.get(index));
                if (index + 1 < merged.size()) {
                    group.addAll(merged.get(index + 1));
                }
                next.add(group);
            }
            merged = next;
        }
        return merged;
    }

    private List<Map<String, Object>> scenesFromTranscriptGroups(List<List<Map<String, Object>>> groups, ShortsPipelineStrategy strategy) {
        List<Map<String, Object>> scenes = new ArrayList<>();
        boolean qaMode = strategy.preferQuestionAnswerCuts();
        String idPrefix = qaMode ? "qa-scene" : "timeline-scene";
        String source = qaMode ? "transcript_qa_fast_lane" : "transcript_timeline_fast_lane";
        String evidenceMode = qaMode ? "TRANSCRIPT_FIRST_QA" : "TRANSCRIPT_TIMELINE_QUICK";
        String continuityReason = qaMode ? "question_answer_timeline_boundary" : "transcript_timeline_boundary";
        int index = 1;
        for (List<Map<String, Object>> group : groups == null ? List.<List<Map<String, Object>>>of() : groups) {
            if (group.isEmpty()) {
                continue;
            }
            double start = roundSeconds(nodeStartSeconds(group.get(0)));
            double end = roundSeconds(Math.max(start + 0.1, nodeEndSeconds(group.get(group.size() - 1))));
            String question = firstQuestionText(group);
            String activeSpeaker = dominantSpeaker(group, Map.of());
            Map<String, Object> scene = new LinkedHashMap<>();
            scene.put("id", "%s-%03d".formatted(idPrefix, index));
            scene.put("index", index);
            scene.put("start", start);
            scene.put("end", end);
            scene.put("durationSeconds", roundSeconds(end - start));
            scene.put("label", question.isBlank() || !qaMode ? "Transcript window " + index : "Q&A " + index);
            scene.put("summary", question.isBlank() ? summarizeTranscriptGroup(group) : question);
            scene.put("source", source);
            scene.put("evidenceMode", evidenceMode);
            scene.put("cutStrategy", strategy.mode());
            scene.put("questionText", question);
            scene.put("transcriptNodeCount", group.size());
            scene.put("dialogue", dialogueNodes(group));
            scene.put("speakerTurns", speakerTurns(group));
            scene.put("activeSpeaker", activeSpeaker);
            scene.put("speakerFocus", speakerFocusMap(activeSpeaker));
            scene.put("speakerFocusCropAnchor", speakerFocusForSpeaker(activeSpeaker));
            scene.put("representativeTimestamp", roundSeconds(start + Math.max(0.0, end - start) / 2.0));
            scene.put("frameIds", new ArrayList<String>());
            scene.put("continuity", Map.of(
                    "status", "TRANSCRIPT_LOCKED",
                    "reason", continuityReason
            ));
            scene.put("questionAnswer", questionAnswerForTimeline(group, scene));
            scene.put("podcastEditStrategy", Map.of(
                    "source", "transcript_qa_timeline",
                    "tokenEfficient", true,
                    "visualScanRequired", false,
                    "cutRule", qaMode ? "question_answer_window" : "complete_transcript_thought",
                    "speakerFocusRule", "crop by transcript speaker label; optional visual analysis can calibrate later"
            ));
            scenes.add(scene);
            index++;
        }
        return scenes;
    }

    private List<Map<String, Object>> attachTranscriptScenes(List<Map<String, Object>> transcript, List<Map<String, Object>> scenes) {
        List<Map<String, Object>> attached = new ArrayList<>();
        for (Map<String, Object> node : transcript == null ? List.<Map<String, Object>>of() : transcript) {
            Map<String, Object> copy = node == null ? new LinkedHashMap<>() : new LinkedHashMap<>(node);
            double midpoint = nodeStartSeconds(copy) + Math.max(0.0, nodeEndSeconds(copy) - nodeStartSeconds(copy)) / 2.0;
            Map<String, Object> scene = sceneAtTimestamp(scenes, midpoint);
            if (!scene.isEmpty()) {
                copy.put("sceneId", scene.get("id"));
                copy.put("sceneStart", scene.get("start"));
                copy.put("sceneEnd", scene.get("end"));
                copy.put("sceneSource", scene.get("source"));
            }
            String speaker = speakerLabel(copy);
            if (mapValue(copy.get("speakerFocus")).isEmpty()) {
                copy.put("speakerFocus", speakerFocusMap(speaker));
            }
            copy.put("speakerFocusCropAnchor", defaultString(firstNonEmpty(copy.get("speakerFocusCropAnchor"), speakerFocusForSpeaker(speaker)), "center"));
            copy.put("frames", List.of());
            attached.add(copy);
        }
        return attached;
    }

    private TranscriptQaSignal transcriptQaSignal(List<Map<String, Object>> transcript) {
        int nodeCount = 0;
        int questionCount = 0;
        int speakerTurnCount = 0;
        String previousSpeaker = "";
        for (Map<String, Object> node : transcript == null ? List.<Map<String, Object>>of() : transcript) {
            if (node == null) {
                continue;
            }
            nodeCount++;
            if (questionLike(nodeText(node))) {
                questionCount++;
            }
            String speaker = defaultString(firstNonEmpty(node.get("speaker"), node.get("role")), "").trim().toLowerCase(Locale.ROOT);
            if (!speaker.isBlank() && !previousSpeaker.isBlank() && !speaker.equals(previousSpeaker)) {
                speakerTurnCount++;
            }
            if (!speaker.isBlank()) {
                previousSpeaker = speaker;
            }
        }
        double ratio = nodeCount == 0 ? 0.0 : ((double) questionCount / (double) nodeCount);
        return new TranscriptQaSignal(questionCount, ratio, speakerTurnCount);
    }

    private boolean questionLike(String text) {
        String normalized = defaultString(text, "").trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return false;
        }
        if (normalized.contains("?")) {
            return true;
        }
        for (String prefix : List.of("what ", "why ", "how ", "when ", "where ", "who ", "which ", "is ", "are ", "was ", "were ", "do ", "does ", "did ", "can ", "could ", "should ", "would ", "will ", "tell me ", "explain ")) {
            if (normalized.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private String firstQuestionText(List<Map<String, Object>> group) {
        for (Map<String, Object> node : group == null ? List.<Map<String, Object>>of() : group) {
            String text = nodeText(node);
            if (questionLike(text)) {
                return truncate(text, 140);
            }
        }
        return "";
    }

    private String summarizeTranscriptGroup(List<Map<String, Object>> group) {
        StringBuilder builder = new StringBuilder();
        for (Map<String, Object> node : group == null ? List.<Map<String, Object>>of() : group) {
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(nodeText(node));
            if (builder.length() >= 140) {
                break;
            }
        }
        return truncate(builder.toString().trim(), 140);
    }

    private String nodeText(Map<String, Object> node) {
        return defaultString(firstNonEmpty(
                node == null ? null : node.get("transcript"),
                node == null ? null : node.get("text"),
                node == null ? null : node.get("summary")
        ), "");
    }

    private double nodeStartSeconds(Map<String, Object> node) {
        return roundSeconds(doubleValue(firstNonEmpty(
                node == null ? null : node.get("start"),
                node == null ? null : node.get("sourceStart"),
                node == null ? null : node.get("timelineStart")
        ), 0.0));
    }

    private double nodeEndSeconds(Map<String, Object> node) {
        double start = nodeStartSeconds(node);
        return roundSeconds(Math.max(start + 0.1, doubleValue(firstNonEmpty(
                node == null ? null : node.get("end"),
                node == null ? null : node.get("sourceEnd"),
                node == null ? null : node.get("timelineEnd")
        ), start + 0.1)));
    }

    private String normalizedType(Object value) {
        return defaultString(value, "unknown").trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    }

    private String dnaSignalText(Map<String, Object> dna) {
        if (dna == null || dna.isEmpty()) {
            return "";
        }
        return dna.toString().toLowerCase(Locale.ROOT);
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

    private record TranscriptRepair(
            List<Map<String, Object>> transcript,
            boolean applied,
            Map<String, Object> metadata
    ) {
    }

    private record SceneEvidenceRepair(
            boolean applied,
            ShortSceneAnalysisService.SceneAnalysisResult sceneAnalysis,
            Map<String, Object> metadata
    ) {
        static SceneEvidenceRepair notApplied() {
            return new SceneEvidenceRepair(false, null, Map.of("applied", false));
        }
    }

    private record StoryEvidenceRepair(
            boolean applied,
            List<Map<String, Object>> chains,
            List<String> goodHookNodes,
            Map<String, Object> metadata
    ) {
        static StoryEvidenceRepair notApplied() {
            return new StoryEvidenceRepair(false, List.of(), List.of(), Map.of("applied", false));
        }
    }

    private record TranscriptQaSignal(int questionCount, double questionRatio, int speakerTurnCount) {
    }

    private record ShortsPipelineStrategy(
            String mode,
            boolean fastLane,
            boolean transcriptFirstScenes,
            boolean skipSceneVisualPrep,
            boolean skipDeepSceneCritic,
            boolean preferQuestionAnswerCuts,
            boolean visualContinuityScenes,
            String sceneAnalysisMode,
            String sceneStageMessage,
            String sceneCriticStageMessage,
            Map<String, Object> metadata
    ) {
    }

    private Map<String, Object> videoNode(String id, int start, int end, String speaker, String transcript, String sceneId, int emotion, int motion, int interestingness, List<String> frames) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", id);
        node.put("start", start);
        node.put("end", end);
        node.put("time", timeLabel(start, end));
        node.put("speaker", speaker);
        node.put("transcript", transcript);
        node.put("sceneId", sceneId);
        node.put("emotion", emotion);
        node.put("motion", motion);
        node.put("interestingness", interestingness);
        node.put("frames", frames);
        return node;
    }

    private Map<String, Object> edge(String from, String to, String type, String reason) {
        Map<String, Object> edge = new LinkedHashMap<>();
        edge.put("from", from);
        edge.put("to", to);
        edge.put("type", type);
        edge.put("reason", reason);
        return edge;
    }

    private Map<String, Object> sourceAssetMap(CreatorAsset asset) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("assetId", asset.getId().toString());
        map.put("assetType", asset.getAssetType());
        map.put("bucket", asset.getBucket());
        map.put("objectKey", asset.getObjectKey());
        map.put("contentType", asset.getContentType());
        map.put("sizeBytes", asset.getSizeBytes());
        map.put("url", asset.getPublicUrl());
        map.put("metadata", mutableMap(asset.getMetadata()));
        return map;
    }

    private Map<String, Object> parseJsonObject(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (JsonProcessingException ex) {
            Map<String, Object> raw = new LinkedHashMap<>();
            raw.put("raw", json);
            return raw;
        }
    }

    private Map<String, Object> defaultMap(Object value, Map<String, Object> fallback) {
        Map<String, Object> map = mapValue(value);
        return map.isEmpty() ? fallback : map;
    }

    private Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), item));
            return result;
        }
        if (value instanceof String string && !string.isBlank()) {
            return parseJsonObject(string);
        }
        return new LinkedHashMap<>();
    }

    private Map<String, Object> mutableMap(Map<String, Object> value) {
        return value == null ? new LinkedHashMap<>() : new LinkedHashMap<>(value);
    }

    @SuppressWarnings("unchecked")
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

    @SuppressWarnings("unchecked")
    private List<Object> objectList(Object value) {
        if (value instanceof List<?> list) {
            return new ArrayList<>((List<Object>) list);
        }
        return new ArrayList<>();
    }

    private void appendReviewEvent(Map<String, Object> metadata, String action, Map<String, Object> payload) {
        List<Object> events = objectList(metadata.get("reviewEvents"));
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("action", action);
        event.put("payload", payload);
        event.put("createdAt", OffsetDateTime.now().toString());
        events.add(event);
        metadata.put("reviewEvents", events);
    }

    private BigDecimal scoreValue(Object value, int rank) {
        BigDecimal fallback = BigDecimal.valueOf(Math.max(70, 96 - (rank * 2)));
        BigDecimal score = decimalValue(value, fallback);
        if (score.compareTo(BigDecimal.ONE) <= 0) {
            score = score.multiply(BigDecimal.valueOf(100));
        }
        return score.setScale(3, RoundingMode.HALF_UP);
    }

    private BigDecimal decimalValue(Object value, BigDecimal fallback) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        if (value == null || String.valueOf(value).isBlank()) {
            return fallback;
        }
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private int intValue(Object value, Integer fallback) {
        int defaultValue = fallback == null ? 0 : fallback;
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null || String.valueOf(value).isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    private String normalizePlatform(String platform) {
        String normalized = defaultString(platform, "youtube_shorts").toLowerCase(Locale.ROOT).trim();
        return switch (normalized) {
            case "youtube", "youtube_shorts" -> "youtube_shorts";
            case "instagram", "instagram_reels" -> "instagram_reels";
            case "tiktok", "linkedin", "x", "facebook_reels" -> normalized;
            default -> "youtube_shorts";
        };
    }

    private int normalizeTargetDuration(Integer duration) {
        int value = duration == null ? 60 : duration;
        if (value <= 30) {
            return 30;
        }
        if (value <= 60) {
            return 60;
        }
        return 90;
    }

    private int normalizeRequestedShorts(Integer requestedShorts) {
        int value = requestedShorts == null ? 20 : requestedShorts;
        return Math.max(1, Math.min(value, 50));
    }

    private String normalizeReviewMode(String reviewMode) {
        String normalized = defaultString(reviewMode, "REVIEW").toUpperCase(Locale.ROOT).trim();
        return "AUTO".equals(normalized) ? "AUTO" : "REVIEW";
    }

    private String normalizeExecutionMode(Object executionMode) {
        String normalized = defaultString(executionMode, "AUTO").toUpperCase(Locale.ROOT).trim().replace('-', '_').replace(' ', '_');
        return "MANUAL_STEP".equals(normalized) || "MANUAL".equals(normalized) || "STEP".equals(normalized)
                ? "MANUAL_STEP"
                : "AUTO";
    }

    private String normalizeReviewAction(String action) {
        String normalized = defaultString(action, "").toUpperCase(Locale.ROOT).trim().replace('-', '_');
        if (normalized.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Review action is required.");
        }
        return normalized;
    }

    private String aspectRatioFor(String platform) {
        return switch (normalizePlatform(platform)) {
            case "linkedin" -> "4:5";
            case "x" -> "1:1";
            default -> "9:16";
        };
    }

    private List<String> safeZonesFor(String platform) {
        return switch (normalizePlatform(platform)) {
            case "linkedin" -> List.of("caption_safe_bottom", "profile_safe_top");
            case "x" -> List.of("square_center_safe", "caption_safe_bottom");
            default -> List.of("top_caption_safe", "bottom_ui_safe", "right_action_rail_safe");
        };
    }

    private String edgeTypeFor(Map<String, Object> videoDna) {
        String structure = defaultString(videoDna.get("structureType"), "storytelling").toLowerCase(Locale.ROOT);
        return switch (structure) {
            case "qa" -> "QUESTION_ANSWER";
            case "instructional" -> "PROBLEM_SOLUTION";
            case "dramatic" -> "ESCALATION";
            case "reveal" -> "CAUSE_EFFECT";
            case "reaction" -> "REACTION_CHAIN";
            default -> "SEQUENTIAL";
        };
    }

    private String chainForHook(String hook) {
        String normalized = hook == null ? "" : hook.toLowerCase(Locale.ROOT);
        if (normalized.contains("question")) {
            return "Q1 + best answer + insight";
        }
        if (normalized.contains("contrarian")) {
            return "Strong opinion + proof + takeaway";
        }
        if (normalized.contains("problem")) {
            return "Problem + solution + result";
        }
        if (normalized.contains("reveal")) {
            return "Reveal + evidence + conclusion";
        }
        return "Aha moment + context + payoff";
    }

    private String traceSummary(String stage, Map<String, Object> videoDna) {
        return switch (stage) {
            case "TRANSCRIPT" -> "Transcript nodes are available for graph and candidate decisions.";
            case "VIDEO_TYPE_CLASSIFICATION" -> "Classified video as " + defaultString(videoDna.get("primaryType"), "unknown") + ".";
            case "VIDEO_GRAPH_BUILDER" -> "Reusable semantic graph built for future 30s, 60s, and 90s generations.";
            case "COMPRESSION" -> "Compression uses content-aware chain preservation instead of random highlights.";
            case "HOOK_GENERATION" -> "Hooks generated per platform and candidate chain.";
            case "CAPTION_PLANNING" -> "Caption plans include density, safe zones, and timing.";
            case "CANDIDATE_RANKING" -> "Candidates ranked by hook clarity, interestingness, continuity, and platform fit.";
            case "RENDERING" -> "Rendering is held until review decisions are approved.";
            case "COMPLETED" -> "Planner completed and candidates are ready for review.";
            default -> stage.toLowerCase(Locale.ROOT).replace('_', ' ') + " completed.";
        };
    }

    private String timeLabel(int start, int end) {
        return secondsLabel(start) + " - " + secondsLabel(end);
    }

    private String secondsLabel(int seconds) {
        int minutes = Math.max(0, seconds) / 60;
        int remainingSeconds = Math.max(0, seconds) % 60;
        return "%02d:%02d".formatted(minutes, remainingSeconds);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            return String.valueOf(value);
        }
    }

    private String safeTenantId(String tenantId) {
        return defaultString(tenantId, "unknown");
    }

    private String safeUserId(String userId) {
        return defaultString(userId, "anonymous");
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String defaultString(Object value, String fallback) {
        if (value == null || String.valueOf(value).isBlank()) {
            return fallback;
        }
        return String.valueOf(value);
    }

    private String sanitizeFilename(String filename) {
        String safe = defaultString(filename, "source-video.mp4")
                .replaceAll("[^A-Za-z0-9._-]", "-")
                .replaceAll("-+", "-");
        if (safe.length() > 180) {
            return safe.substring(safe.length() - 180);
        }
        return safe;
    }

    private String extension(String filename) {
        int index = filename == null ? -1 : filename.lastIndexOf('.');
        if (index < 0 || index == filename.length() - 1) {
            return "";
        }
        return filename.substring(index + 1).toLowerCase(Locale.ROOT);
    }

    private String extensionFor(String contentType, String objectKey, String fallback) {
        String key = defaultString(objectKey, "").toLowerCase(Locale.ROOT);
        int dot = key.lastIndexOf('.');
        if (dot >= 0 && dot < key.length() - 1) {
            return key.substring(dot);
        }
        String normalized = defaultString(contentType, "").toLowerCase(Locale.ROOT);
        if (normalized.contains("quicktime")) return ".mov";
        if (normalized.contains("webm")) return ".webm";
        if (normalized.contains("matroska")) return ".mkv";
        if (normalized.contains("avi")) return ".avi";
        return defaultString(fallback, ".mp4");
    }

    private String stripExtension(String filename) {
        String safe = defaultString(filename, "Source video");
        int index = safe.lastIndexOf('.');
        return index > 0 ? safe.substring(0, index) : safe;
    }

    private String truncate(String value, int maxLength) {
        String safe = defaultString(value, "");
        if (safe.length() <= maxLength) {
            return safe;
        }
        return safe.substring(0, maxLength);
    }
}
