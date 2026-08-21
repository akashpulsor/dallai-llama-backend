package com.dalai.llama.creativeplanning.controller;

import com.dalai.llama.creativeplanning.dto.CreateProductRequest;
import com.dalai.llama.creativeplanning.dto.ProductJourneyView;
import com.dalai.llama.creativeplanning.dto.ProductProfileView;
import com.dalai.llama.creativeplanning.dto.ProductReferenceImageView;
import com.dalai.llama.creativeplanning.service.ProductJourneyService;
import com.dalai.llama.creativeplanning.service.ProductProfileService;
import com.dalai.llama.creativeplanning.service.ProductReferenceImageService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@RestController
public class ProductController extends BaseController {

    private final ProductProfileService productProfileService;
    private final ProductReferenceImageService productReferenceImageService;
    private final ProductJourneyService productJourneyService;

    public ProductController(
            ProductProfileService productProfileService,
            ProductReferenceImageService productReferenceImageService,
            ProductJourneyService productJourneyService
    ) {
        this.productProfileService = productProfileService;
        this.productReferenceImageService = productReferenceImageService;
        this.productJourneyService = productJourneyService;
    }

    @PostMapping("/v1/products")
    public ResponseEntity<ProductProfileView> create(@Valid @RequestBody CreateProductRequest request) {
        return ResponseEntity.ok(productProfileService.create(tenant().tenantId(), request));
    }

    @GetMapping("/v1/products")
    public ResponseEntity<List<ProductProfileView>> list() {
        return ResponseEntity.ok(productProfileService.list(tenant().tenantId()));
    }

    @PostMapping(path = "/v1/products/{productId}/reference-images", consumes = "multipart/form-data")
    public ResponseEntity<ProductReferenceImageView> uploadReferenceImage(
            @PathVariable UUID productId, @RequestParam("file") MultipartFile file) {
        return ResponseEntity.ok(productReferenceImageService.upload(tenant().tenantId(), productId, file));
    }

    @GetMapping("/v1/products/{productId}/reference-images")
    public ResponseEntity<List<ProductReferenceImageView>> listReferenceImages(@PathVariable UUID productId) {
        return ResponseEntity.ok(productReferenceImageService.list(tenant().tenantId(), productId));
    }

    @GetMapping("/v1/products/{productId}/journey")
    public ResponseEntity<ProductJourneyView> journey(@PathVariable UUID productId) {
        return ResponseEntity.ok(productJourneyService.journey(tenant().tenantId(), productId));
    }
}
