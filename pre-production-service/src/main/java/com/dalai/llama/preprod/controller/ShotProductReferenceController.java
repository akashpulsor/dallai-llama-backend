package com.dalai.llama.preprod.controller;

import com.dalai.llama.preprod.domain.ProductReferenceClassification;
import com.dalai.llama.preprod.dto.AnalyzeShotProductReferenceView;
import com.dalai.llama.preprod.dto.ConfirmShotProductReferenceRequest;
import com.dalai.llama.preprod.dto.ShotProductReferenceView;
import com.dalai.llama.preprod.service.ShotProductReferenceService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

@RestController
public class ShotProductReferenceController extends BaseController {

    private final ShotProductReferenceService shotProductReferenceService;

    public ShotProductReferenceController(ShotProductReferenceService shotProductReferenceService) {
        this.shotProductReferenceService = shotProductReferenceService;
    }

    @PostMapping(path = "/v1/shots/{shotId}/product-reference/analyze", consumes = "multipart/form-data")
    public ResponseEntity<AnalyzeShotProductReferenceView> analyze(
            @PathVariable UUID shotId,
            @RequestParam ProductReferenceClassification classification,
            @RequestParam("file") MultipartFile file
    ) {
        return ResponseEntity.ok(shotProductReferenceService.analyze(tenant().tenantId(), shotId, classification, file));
    }

    @PostMapping("/v1/shots/{shotId}/product-reference/confirm")
    public ResponseEntity<ShotProductReferenceView> confirm(
            @PathVariable UUID shotId, @Valid @RequestBody ConfirmShotProductReferenceRequest request) {
        return ResponseEntity.ok(shotProductReferenceService.confirm(tenant().tenantId(), shotId, request));
    }

    @GetMapping("/v1/shots/{shotId}/product-reference")
    public ResponseEntity<ShotProductReferenceView> get(@PathVariable UUID shotId) {
        return ResponseEntity.ok(shotProductReferenceService.get(tenant().tenantId(), shotId));
    }
}
