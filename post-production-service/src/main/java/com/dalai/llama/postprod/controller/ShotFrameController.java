package com.dalai.llama.postprod.controller;

import com.dalai.llama.postprod.domain.FrameExtractionMode;
import com.dalai.llama.postprod.dto.ShotFrameExtractionResult;
import com.dalai.llama.postprod.dto.ShotFrameView;
import com.dalai.llama.postprod.service.ShotFrameService;
import com.dalai.llama.postprod.web.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Frames of a shot's current cut. {@code mode} LAST_FRAME is what the video studio uses to start
 * the next shot exactly where this one ends; SAMPLE (with {@code sampleFps}) and ALL segment the
 * whole clip. {@code projectId} is needed only for a shot never cut before, whose generated clip
 * is looked up and imported as its first cut.
 */
@RestController
@RequiredArgsConstructor
public class ShotFrameController {

    private final ShotFrameService shotFrameService;

    @PostMapping("/v1/post-production/shots/{shotId}/frames")
    public ResponseEntity<ShotFrameExtractionResult> extract(
            @PathVariable UUID shotId,
            @RequestParam UUID projectId,
            @RequestParam(defaultValue = "LAST_FRAME") FrameExtractionMode mode,
            @RequestParam(required = false) Integer sampleFps) {
        UUID tenantId = TenantContextHolder.get().tenantId();
        return ResponseEntity.ok(shotFrameService.extract(tenantId, projectId, shotId, mode, sampleFps));
    }

    @GetMapping("/v1/post-production/shots/{shotId}/frames")
    public ResponseEntity<List<ShotFrameView>> list(@PathVariable UUID shotId) {
        return ResponseEntity.ok(shotFrameService.list(TenantContextHolder.get().tenantId(), shotId));
    }

    /** For video-generation-service, which has no user token to forward. Never routed publicly. */
    @PostMapping("/api/v1/internal/tenants/{tenantId}/projects/{projectId}/shots/{shotId}/frames")
    public ResponseEntity<ShotFrameExtractionResult> extractInternal(
            @PathVariable UUID tenantId,
            @PathVariable UUID projectId,
            @PathVariable UUID shotId,
            @RequestParam(defaultValue = "LAST_FRAME") FrameExtractionMode mode,
            @RequestParam(required = false) Integer sampleFps) {
        return ResponseEntity.ok(shotFrameService.extract(tenantId, projectId, shotId, mode, sampleFps));
    }
}
