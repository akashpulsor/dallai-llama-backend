package com.dalai.llama.preprod.controller;

import com.dalai.llama.preprod.dto.DispatchShotRequest;
import com.dalai.llama.preprod.dto.GenerationThoughtView;
import com.dalai.llama.preprod.dto.ShotDispatchResponse;
import com.dalai.llama.preprod.dto.ShotView;
import com.dalai.llama.preprod.service.GenerationThoughtService;
import com.dalai.llama.preprod.service.ShotContextAssemblyService;
import com.dalai.llama.preprod.service.ShotListGenerationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
public class ShotListController extends BaseController {

    private final ShotListGenerationService shotListGenerationService;
    private final ShotContextAssemblyService shotContextAssemblyService;
    private final GenerationThoughtService generationThoughtService;

    public ShotListController(
            ShotListGenerationService shotListGenerationService,
            ShotContextAssemblyService shotContextAssemblyService,
            GenerationThoughtService generationThoughtService
    ) {
        this.shotListGenerationService = shotListGenerationService;
        this.shotContextAssemblyService = shotContextAssemblyService;
        this.generationThoughtService = generationThoughtService;
    }

    @PostMapping("/v1/projects/{projectId}/shots/generate-list")
    public ResponseEntity<List<ShotView>> generateList(@PathVariable UUID projectId) {
        return ResponseEntity.ok(shotListGenerationService.generate(tenant().tenantId(), projectId));
    }

    @GetMapping("/v1/projects/{projectId}/shots")
    public ResponseEntity<List<ShotView>> list(@PathVariable UUID projectId) {
        return ResponseEntity.ok(shotListGenerationService.list(tenant().tenantId(), projectId));
    }

    @PostMapping("/v1/shots/{shotId}/generate")
    public ResponseEntity<ShotDispatchResponse> dispatch(@PathVariable UUID shotId, @RequestBody(required = false) DispatchShotRequest request) {
        boolean autoApprove = request != null && request.autoApprove();
        Boolean dialogue = request == null ? null : request.dialogue();
        Boolean captions = request == null ? null : request.captions();
        return ResponseEntity.ok(shotContextAssemblyService.dispatch(tenant().tenantId(), shotId, autoApprove, dialogue, captions));
    }

    @GetMapping("/v1/shots/{shotId}/thoughts")
    public ResponseEntity<List<GenerationThoughtView>> thoughts(@PathVariable UUID shotId) {
        return ResponseEntity.ok(generationThoughtService.list(shotId));
    }
}
