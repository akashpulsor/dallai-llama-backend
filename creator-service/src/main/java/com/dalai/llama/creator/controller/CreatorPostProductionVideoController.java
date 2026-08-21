package com.dalai.llama.creator.controller;

import com.dalai.llama.creator.dto.response.GenerationJobResponse;
import com.dalai.llama.creator.service.CreatorPostProductionVideoDecomposeService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * New, not-yet-consumed-by-any-caller endpoint for the upcoming Editor flow: takes a
 * video, samples it into thumbnail frames, and decouples its audio into a separate
 * downloadable asset. Poll the returned job via GET /api/v1/creator/jobs/{jobId}
 * (same generic job endpoint every other *-async route in this codebase uses) - the
 * completed output payload has "frames", "frameMetadata", "sourceVideo", "audioAsset".
 */
@RestController
@RequestMapping("/api/v1/creator/post-production/videos")
public class CreatorPostProductionVideoController {

    private final CreatorPostProductionVideoDecomposeService decomposeService;

    public CreatorPostProductionVideoController(CreatorPostProductionVideoDecomposeService decomposeService) {
        this.decomposeService = decomposeService;
    }

    @PostMapping(value = "/decompose-async", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<GenerationJobResponse> decomposeAsync(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "frameCount", required = false) Integer frameCount,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity
                .status(HttpStatus.ACCEPTED)
                .body(decomposeService.startDecompose(file, frameCount, tenantId, userId));
    }
}
