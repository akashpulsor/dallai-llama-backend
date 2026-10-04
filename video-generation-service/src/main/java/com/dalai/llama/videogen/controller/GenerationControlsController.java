package com.dalai.llama.videogen.controller;

import com.dalai.llama.videogen.dto.generationplan.GenerationControlsView;
import com.dalai.llama.videogen.dto.generationplan.ShotGenerationControlsView;
import com.dalai.llama.videogen.service.GenerationControlsService;
import com.dalai.llama.videogen.web.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Generation controls: the project's defaults (the Video tab console) and each shot's own set (the
 * video studio). A shot without its own set follows the defaults. Under /v1/scenes because
 * /v1/projects/** routes to pre-production-service at the gateway.
 */
@RestController
@RequiredArgsConstructor
public class GenerationControlsController {

    private final GenerationControlsService service;

    @GetMapping("/v1/scenes/projects/{projectId}/generation-controls")
    public ResponseEntity<GenerationControlsView> get(@PathVariable UUID projectId) {
        return ResponseEntity.ok(service.forProject(TenantContextHolder.get().tenantId(), projectId));
    }

    /** The whole set, every time: a console that sends only what changed cannot reset a switch. */
    @PutMapping("/v1/scenes/projects/{projectId}/generation-controls")
    public ResponseEntity<GenerationControlsView> update(@PathVariable UUID projectId,
                                                         @RequestBody GenerationControlsView controls) {
        return ResponseEntity.ok(service.update(TenantContextHolder.get().tenantId(), projectId, controls));
    }

    /** The controls this shot generates with; {@code custom} false means the project's defaults. */
    @GetMapping("/v1/scenes/projects/{projectId}/shots/{shotId}/generation-controls")
    public ResponseEntity<ShotGenerationControlsView> getShot(@PathVariable UUID projectId, @PathVariable UUID shotId) {
        return ResponseEntity.ok(service.shotView(TenantContextHolder.get().tenantId(), projectId, shotId));
    }

    /** Gives the shot its own set -- the whole set, every time. */
    @PutMapping("/v1/scenes/projects/{projectId}/shots/{shotId}/generation-controls")
    public ResponseEntity<ShotGenerationControlsView> updateShot(@PathVariable UUID projectId, @PathVariable UUID shotId,
                                                                 @RequestBody GenerationControlsView controls) {
        return ResponseEntity.ok(service.updateShot(TenantContextHolder.get().tenantId(), projectId, shotId, controls));
    }

    /** Back to the project's defaults. */
    @DeleteMapping("/v1/scenes/projects/{projectId}/shots/{shotId}/generation-controls")
    public ResponseEntity<ShotGenerationControlsView> resetShot(@PathVariable UUID projectId, @PathVariable UUID shotId) {
        return ResponseEntity.ok(service.resetShot(TenantContextHolder.get().tenantId(), projectId, shotId));
    }
}
