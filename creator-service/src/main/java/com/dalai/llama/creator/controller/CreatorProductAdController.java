package com.dalai.llama.creator.controller;

import com.dalai.llama.creator.dto.request.GenerateProductAdPipelineRequest;
import com.dalai.llama.creator.dto.response.GenerationJobResponse;
import com.dalai.llama.creator.service.GenerationJobService;
import com.dalai.llama.creator.service.ProductAdPipelineService;
import com.dalai.llama.creator.service.ProductAdResearchService;
import com.dalai.llama.creator.service.ProductReferenceImageService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
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
@RequestMapping("/api/v1/creator/product-ads")
public class CreatorProductAdController {

    private final ProductAdResearchService productAdResearchService;
    private final ProductAdPipelineService productAdPipelineService;
    private final GenerationJobService generationJobService;
    private final ProductReferenceImageService productReferenceImageService;

    public CreatorProductAdController(
            ProductAdResearchService productAdResearchService,
            ProductAdPipelineService productAdPipelineService,
            GenerationJobService generationJobService,
            ProductReferenceImageService productReferenceImageService
    ) {
        this.productAdResearchService = productAdResearchService;
        this.productAdPipelineService = productAdPipelineService;
        this.generationJobService = generationJobService;
        this.productReferenceImageService = productReferenceImageService;
    }

    @PostMapping(value = "/reference-images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> uploadReferenceImages(
            @RequestParam("files") List<MultipartFile> files,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(productReferenceImageService.upload(files, tenantId, userId));
    }

    @PostMapping("/research")
    public ResponseEntity<Map<String, Object>> research(
            @Valid @RequestBody(required = false) GenerateProductAdPipelineRequest request,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity.ok(productAdResearchService.buildProductAdPlan(request, tenantId, userId, null));
    }

    @PostMapping("/pipeline/generate-async")
    public ResponseEntity<GenerationJobResponse> generatePipelineAsync(
            @Valid @RequestBody(required = false) GenerateProductAdPipelineRequest request,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity
                .status(HttpStatus.ACCEPTED)
                .body(generationJobService.toResponse(productAdPipelineService.startPipeline(request, tenantId, userId)));
    }

    @GetMapping("/assets")
    public ResponseEntity<List<Map<String, Object>>> listAssets(
            @RequestParam(value = "jobId", required = false) UUID jobId,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity.ok(productAdPipelineService.listAssets(jobId, tenantId, userId));
    }
}
