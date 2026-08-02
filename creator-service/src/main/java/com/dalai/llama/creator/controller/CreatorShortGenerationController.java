package com.dalai.llama.creator.controller;

import com.dalai.llama.creator.dto.request.GenerateShortsRequest;
import com.dalai.llama.creator.dto.request.CompleteShortUploadRequest;
import com.dalai.llama.creator.dto.request.CompleteShortMultipartUploadRequest;
import com.dalai.llama.creator.dto.request.ShortUploadSessionRequest;
import com.dalai.llama.creator.dto.request.ShortUploadPartUrlRequest;
import com.dalai.llama.creator.dto.request.ShortCandidateReviewRequest;
import com.dalai.llama.creator.dto.response.ShortCandidateResponse;
import com.dalai.llama.creator.dto.response.ShortGenerationResponse;
import com.dalai.llama.creator.dto.response.ShortMultipartUploadSessionResponse;
import com.dalai.llama.creator.dto.response.ShortUploadSessionResponse;
import com.dalai.llama.creator.dto.response.ShortUploadPartUrlResponse;
import com.dalai.llama.creator.service.CreatorShortGenerationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/creator/shorts")
public class CreatorShortGenerationController {

    private static final Logger log = LoggerFactory.getLogger(CreatorShortGenerationController.class);

    private final CreatorShortGenerationService shortGenerationService;

    public CreatorShortGenerationController(CreatorShortGenerationService shortGenerationService) {
        this.shortGenerationService = shortGenerationService;
    }

    @PostMapping(value = "/generate", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ShortGenerationResponse> generateShorts(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "projectId", required = false) UUID projectId,
            @RequestParam(value = "title", required = false) String title,
            @RequestParam(value = "platform", required = false) String platform,
            @RequestParam(value = "targetDurationSeconds", required = false) Integer targetDurationSeconds,
            @RequestParam(value = "requestedShorts", required = false) Integer requestedShorts,
            @RequestParam(value = "reviewMode", required = false) String reviewMode,
            @RequestParam(value = "creatorProfile", required = false) String creatorProfileJson,
            @RequestParam(value = "notes", required = false) String notes,
            @RequestParam(value = "executionMode", required = false) String executionMode,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-User-ID", required = false) String userIdHeader,
            Authentication authentication
    ) {
        String userId = resolveUserId(authentication, userIdHeader);
        log.info(
                "Generate Shorts upload request received tenantId={} userId={} filename={} contentType={} sizeBytes={} requestedShorts={} targetDurationSeconds={} platform={} reviewMode={}",
                tenantId,
                userId,
                file == null ? "" : file.getOriginalFilename(),
                file == null ? "" : file.getContentType(),
                file == null ? 0L : file.getSize(),
                requestedShorts,
                targetDurationSeconds,
                platform,
                reviewMode
        );
        GenerateShortsRequest request = new GenerateShortsRequest(
                projectId,
                title,
                platform,
                targetDurationSeconds,
                requestedShorts,
                reviewMode,
                creatorProfileJson,
                notes,
                executionMode
        );
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(shortGenerationService.startGeneration(file, request, tenantId, userId));
    }

    @PostMapping("/uploads")
    public ResponseEntity<ShortUploadSessionResponse> createChunkUpload(
            @RequestBody(required = false) ShortUploadSessionRequest request,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-User-ID", required = false) String userIdHeader,
            Authentication authentication
    ) {
        String userId = resolveUserId(authentication, userIdHeader);
        log.info("Generate Shorts chunk upload session requested tenantId={} userId={}", tenantId, userId);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(shortGenerationService.createChunkUpload(request, tenantId, userId));
    }

    @PostMapping("/uploads/multipart")
    public ResponseEntity<ShortMultipartUploadSessionResponse> createMultipartUpload(
            @RequestBody(required = false) ShortUploadSessionRequest request,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-User-ID", required = false) String userIdHeader,
            Authentication authentication
    ) {
        String userId = resolveUserId(authentication, userIdHeader);
        log.info("Generate Shorts direct multipart upload session requested tenantId={} userId={}", tenantId, userId);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(shortGenerationService.createMultipartUpload(request, tenantId, userId));
    }

    @PostMapping("/uploads/{uploadId}/multipart/parts/{partNumber}/presign")
    public ResponseEntity<ShortUploadPartUrlResponse> presignMultipartUploadPart(
            @PathVariable UUID uploadId,
            @PathVariable int partNumber,
            @RequestBody(required = false) ShortUploadPartUrlRequest request,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-User-ID", required = false) String userIdHeader,
            Authentication authentication
    ) {
        String userId = resolveUserId(authentication, userIdHeader);
        log.info(
                "Generate Shorts direct multipart part URL requested uploadId={} partNumber={} tenantId={} userId={}",
                uploadId,
                partNumber,
                tenantId,
                userId
        );
        return ResponseEntity.ok(shortGenerationService.presignMultipartUploadPart(uploadId, partNumber, request, tenantId, userId));
    }

    @PostMapping("/uploads/{uploadId}/multipart/complete")
    public ResponseEntity<ShortGenerationResponse> completeMultipartUpload(
            @PathVariable UUID uploadId,
            @RequestBody(required = false) CompleteShortMultipartUploadRequest request,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-User-ID", required = false) String userIdHeader,
            Authentication authentication
    ) {
        String userId = resolveUserId(authentication, userIdHeader);
        log.info("Generate Shorts direct multipart upload complete requested uploadId={} tenantId={} userId={}", uploadId, tenantId, userId);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(shortGenerationService.completeMultipartUpload(uploadId, request, tenantId, userId));
    }

    @PostMapping(value = "/uploads/{uploadId}/chunks/{chunkIndex}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ShortUploadSessionResponse> uploadChunk(
            @PathVariable UUID uploadId,
            @PathVariable int chunkIndex,
            @RequestParam("chunk") MultipartFile chunk,
            @RequestParam(value = "totalChunks", required = false) Integer totalChunks,
            @RequestParam(value = "sizeBytes", required = false) Long sizeBytes,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-User-ID", required = false) String userIdHeader,
            Authentication authentication
    ) {
        String userId = resolveUserId(authentication, userIdHeader);
        log.info(
                "Generate Shorts chunk received uploadId={} chunkIndex={} tenantId={} userId={} contentType={} sizeBytes={} totalChunks={}",
                uploadId,
                chunkIndex,
                tenantId,
                userId,
                chunk == null ? "" : chunk.getContentType(),
                chunk == null ? 0L : chunk.getSize(),
                totalChunks
        );
        return ResponseEntity.ok(shortGenerationService.uploadChunk(uploadId, chunkIndex, chunk, totalChunks, sizeBytes, tenantId, userId));
    }

    @PostMapping("/uploads/{uploadId}/complete")
    public ResponseEntity<ShortGenerationResponse> completeChunkUpload(
            @PathVariable UUID uploadId,
            @RequestBody(required = false) CompleteShortUploadRequest request,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-User-ID", required = false) String userIdHeader,
            Authentication authentication
    ) {
        String userId = resolveUserId(authentication, userIdHeader);
        log.info("Generate Shorts chunk upload complete requested uploadId={} tenantId={} userId={}", uploadId, tenantId, userId);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(shortGenerationService.completeChunkUpload(uploadId, request, tenantId, userId));
    }
    @GetMapping
    public ResponseEntity<List<ShortGenerationResponse>> listShortVideos(
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-User-ID", required = false) String userIdHeader,
            Authentication authentication
    ) {
        String userId = resolveUserId(authentication, userIdHeader);
        return ResponseEntity.ok(shortGenerationService.listVideos(tenantId, userId));
    }

    @GetMapping("/{videoId}")
    public ResponseEntity<ShortGenerationResponse> getShortVideo(
            @PathVariable UUID videoId,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-User-ID", required = false) String userIdHeader,
            Authentication authentication
    ) {
        String userId = resolveUserId(authentication, userIdHeader);
        return ResponseEntity.ok(shortGenerationService.getVideo(videoId, tenantId, userId));
    }

    @GetMapping("/{videoId}/candidates")
    public ResponseEntity<List<ShortCandidateResponse>> listCandidates(
            @PathVariable UUID videoId,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-User-ID", required = false) String userIdHeader,
            Authentication authentication
    ) {
        String userId = resolveUserId(authentication, userIdHeader);
        return ResponseEntity.ok(shortGenerationService.listCandidates(videoId, tenantId, userId));
    }

    @PostMapping("/{videoId}/pause")
    public ResponseEntity<ShortGenerationResponse> pauseGeneration(
            @PathVariable UUID videoId,
            @RequestBody(required = false) Map<String, Object> payload,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-User-ID", required = false) String userIdHeader,
            Authentication authentication
    ) {
        String userId = resolveUserId(authentication, userIdHeader);
        return ResponseEntity.ok(shortGenerationService.pauseGeneration(videoId, payload, tenantId, userId));
    }

    @PostMapping("/{videoId}/timeline")
    public ResponseEntity<ShortGenerationResponse> saveProcessingTimeline(
            @PathVariable UUID videoId,
            @RequestBody(required = false) Map<String, Object> payload,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-User-ID", required = false) String userIdHeader,
            Authentication authentication
    ) {
        String userId = resolveUserId(authentication, userIdHeader);
        return ResponseEntity.ok(shortGenerationService.saveProcessingTimeline(videoId, payload, tenantId, userId));
    }

    @PostMapping("/{videoId}/resume")
    public ResponseEntity<ShortGenerationResponse> resumeGeneration(
            @PathVariable UUID videoId,
            @RequestBody(required = false) Map<String, Object> payload,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-User-ID", required = false) String userIdHeader,
            Authentication authentication
    ) {
        String userId = resolveUserId(authentication, userIdHeader);
        return ResponseEntity.ok(shortGenerationService.resumeGeneration(videoId, payload, tenantId, userId));
    }

    @PostMapping("/{videoId}/restart")
    public ResponseEntity<ShortGenerationResponse> restartGeneration(
            @PathVariable UUID videoId,
            @RequestBody(required = false) Map<String, Object> payload,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-User-ID", required = false) String userIdHeader,
            Authentication authentication
    ) {
        String userId = resolveUserId(authentication, userIdHeader);
        return ResponseEntity.ok(shortGenerationService.restartGeneration(videoId, payload, tenantId, userId));
    }

    @PostMapping("/{videoId}/visual-analysis")
    public ResponseEntity<ShortGenerationResponse> requestVisualAnalysis(
            @PathVariable UUID videoId,
            @RequestBody(required = false) Map<String, Object> payload,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-User-ID", required = false) String userIdHeader,
            Authentication authentication
    ) {
        String userId = resolveUserId(authentication, userIdHeader);
        return ResponseEntity.ok(shortGenerationService.requestVisualAnalysis(videoId, payload, tenantId, userId));
    }

    @PostMapping("/{videoId}/candidates/{candidateId}/review")
    public ResponseEntity<ShortGenerationResponse> reviewCandidate(
            @PathVariable UUID videoId,
            @PathVariable UUID candidateId,
            @RequestBody(required = false) ShortCandidateReviewRequest request,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-User-ID", required = false) String userIdHeader,
            Authentication authentication
    ) {
        String userId = resolveUserId(authentication, userIdHeader);
        return ResponseEntity.ok(shortGenerationService.reviewCandidate(videoId, candidateId, request, tenantId, userId));
    }

    private String resolveUserId(Authentication authentication, String userIdHeader) {
        if (authentication != null && authentication.getName() != null && !authentication.getName().isBlank()) {
            return authentication.getName();
        }
        if (userIdHeader != null && !userIdHeader.isBlank()) {
            return userIdHeader;
        }
        return "anonymous";
    }
}
