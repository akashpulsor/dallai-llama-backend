package com.dalai.llama.postprod.controller;

import com.dalai.llama.postprod.domain.FrameExtractionMode;
import com.dalai.llama.postprod.dto.ShotFrameExtractionResult;
import com.dalai.llama.postprod.dto.ShotFrameView;
import com.dalai.llama.postprod.service.ShotFrameService;
import com.dalai.llama.postprod.web.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
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
 * whole clip.
 *
 * <p>Asking queues the work (202, with a request id to poll) unless the frame is already stored for
 * the shot's current cut, which is answered at once (200). {@code projectId} is used only for a
 * shot never cut before, whose generated clip is looked up and imported as its first cut.
 */
@RestController
@RequiredArgsConstructor
public class ShotFrameController {

    private final ShotFrameService shotFrameService;

    @PostMapping("/v1/post-production/shots/{shotId}/frames")
    public ResponseEntity<ShotFrameExtractionResult> request(
            @PathVariable UUID shotId,
            @RequestParam UUID projectId,
            @RequestParam(defaultValue = "LAST_FRAME") FrameExtractionMode mode,
            @RequestParam(required = false) Integer sampleFps) {
        return answer(shotFrameService.request(TenantContextHolder.get().tenantId(), projectId, shotId, mode, sampleFps));
    }

    @GetMapping("/v1/post-production/shots/{shotId}/frames/requests/{requestId}")
    public ResponseEntity<ShotFrameExtractionResult> status(@PathVariable UUID shotId, @PathVariable UUID requestId) {
        return ResponseEntity.ok(shotFrameService.status(TenantContextHolder.get().tenantId(), requestId));
    }

    @GetMapping("/v1/post-production/shots/{shotId}/frames")
    public ResponseEntity<List<ShotFrameView>> list(@PathVariable UUID shotId) {
        return ResponseEntity.ok(shotFrameService.list(TenantContextHolder.get().tenantId(), shotId));
    }

    /** For video-generation-service, which has no user token to forward. Never routed publicly. */
    @PostMapping("/api/v1/internal/tenants/{tenantId}/projects/{projectId}/shots/{shotId}/frames")
    public ResponseEntity<ShotFrameExtractionResult> requestInternal(
            @PathVariable UUID tenantId,
            @PathVariable UUID projectId,
            @PathVariable UUID shotId,
            @RequestParam(defaultValue = "LAST_FRAME") FrameExtractionMode mode,
            @RequestParam(required = false) Integer sampleFps) {
        return answer(shotFrameService.request(tenantId, projectId, shotId, mode, sampleFps));
    }

    @GetMapping("/api/v1/internal/tenants/{tenantId}/frames/requests/{requestId}")
    public ResponseEntity<ShotFrameExtractionResult> statusInternal(@PathVariable UUID tenantId, @PathVariable UUID requestId) {
        return ResponseEntity.ok(shotFrameService.status(tenantId, requestId));
    }

    private static ResponseEntity<ShotFrameExtractionResult> answer(ShotFrameExtractionResult result) {
        return ResponseEntity.status("COMPLETED".equals(result.status()) ? HttpStatus.OK : HttpStatus.ACCEPTED).body(result);
    }
}
