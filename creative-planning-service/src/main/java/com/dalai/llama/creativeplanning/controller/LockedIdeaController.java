package com.dalai.llama.creativeplanning.controller;

import com.dalai.llama.creativeplanning.dto.BrandPlanExportView;
import com.dalai.llama.creativeplanning.dto.LockedIdeaView;
import com.dalai.llama.creativeplanning.service.LockedIdeaService;
import com.dalai.llama.creativeplanning.service.export.BrandPlanExportService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class LockedIdeaController extends BaseController {

    private final LockedIdeaService lockedIdeaService;
    private final BrandPlanExportService brandPlanExportService;

    public LockedIdeaController(LockedIdeaService lockedIdeaService, BrandPlanExportService brandPlanExportService) {
        this.lockedIdeaService = lockedIdeaService;
        this.brandPlanExportService = brandPlanExportService;
    }

    @GetMapping("/v1/locked-ideas/{lockedIdeaId}")
    public ResponseEntity<LockedIdeaView> get(@PathVariable UUID lockedIdeaId) {
        return ResponseEntity.ok(lockedIdeaService.get(tenant().tenantId(), lockedIdeaId));
    }

    @PostMapping("/v1/locked-ideas/{lockedIdeaId}/export-pdf")
    public ResponseEntity<BrandPlanExportView> exportPdf(@PathVariable UUID lockedIdeaId) {
        return ResponseEntity.ok(brandPlanExportService.exportPdf(tenant().tenantId(), lockedIdeaId));
    }
}
