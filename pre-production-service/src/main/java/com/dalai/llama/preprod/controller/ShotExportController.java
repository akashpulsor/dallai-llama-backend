package com.dalai.llama.preprod.controller;

import com.dalai.llama.preprod.dto.ExportView;
import com.dalai.llama.preprod.service.AnimatedPreviewService;
import com.dalai.llama.preprod.service.ShotExportService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class ShotExportController extends BaseController {

    private final ShotExportService shotExportService;
    private final AnimatedPreviewService animatedPreviewService;

    public ShotExportController(ShotExportService shotExportService, AnimatedPreviewService animatedPreviewService) {
        this.shotExportService = shotExportService;
        this.animatedPreviewService = animatedPreviewService;
    }

    @PostMapping("/v1/projects/{projectId}/export-pdf")
    public ResponseEntity<ExportView> exportPdf(@PathVariable UUID projectId) {
        return ResponseEntity.ok(shotExportService.exportPdf(tenant().tenantId(), projectId));
    }

    /** Rendered inline so the creator can open it straight in a browser tab (or copy the URL to
     * send) -- same "text/html, Content-Disposition: inline" convention creator-service's old
     * animated-preview endpoint used. */
    @GetMapping("/v1/projects/{projectId}/animated-preview")
    public ResponseEntity<String> animatedPreview(@PathVariable UUID projectId) {
        String html = animatedPreviewService.buildAnimatedPreview(tenant().tenantId(), projectId);
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"storyboard-animated.html\"")
                .body(html);
    }
}
