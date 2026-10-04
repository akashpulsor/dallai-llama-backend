package com.dalai.llama.videogen.controller;

import com.dalai.llama.videogen.dto.generationplan.GenerationControlsView;
import com.dalai.llama.videogen.service.GenerationControlsService;
import com.dalai.llama.videogen.web.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The Video tab's generation controls console. Under /v1/scenes because /v1/projects/** routes to
 * pre-production-service at the gateway.
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
}
