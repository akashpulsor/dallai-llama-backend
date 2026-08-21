package com.dalai.llama.creativeplanning.controller;

import com.dalai.llama.creativeplanning.dto.BrandContextView;
import com.dalai.llama.creativeplanning.dto.CreateBrandContextRequest;
import com.dalai.llama.creativeplanning.service.BrandContextService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BrandContextController extends BaseController {

    private final BrandContextService brandContextService;

    public BrandContextController(BrandContextService brandContextService) {
        this.brandContextService = brandContextService;
    }

    @PutMapping("/v1/brand-context")
    public ResponseEntity<BrandContextView> upsert(@Valid @RequestBody CreateBrandContextRequest request) {
        return ResponseEntity.ok(brandContextService.upsert(tenant().tenantId(), request));
    }

    @GetMapping("/v1/brand-context")
    public ResponseEntity<BrandContextView> get() {
        return ResponseEntity.ok(brandContextService.get(tenant().tenantId()));
    }
}
