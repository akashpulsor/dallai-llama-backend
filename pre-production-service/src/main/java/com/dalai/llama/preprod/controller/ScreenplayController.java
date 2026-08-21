package com.dalai.llama.preprod.controller;

import com.dalai.llama.preprod.dto.ScreenplayView;
import com.dalai.llama.preprod.service.ScreenplayGenerationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class ScreenplayController extends BaseController {

    private final ScreenplayGenerationService screenplayGenerationService;

    public ScreenplayController(ScreenplayGenerationService screenplayGenerationService) {
        this.screenplayGenerationService = screenplayGenerationService;
    }

    @PostMapping("/v1/projects/{projectId}/screenplay/generate")
    public ResponseEntity<ScreenplayView> generate(@PathVariable UUID projectId) {
        return ResponseEntity.ok(screenplayGenerationService.generate(tenant().tenantId(), projectId));
    }

    @GetMapping("/v1/projects/{projectId}/screenplay")
    public ResponseEntity<ScreenplayView> get(@PathVariable UUID projectId) {
        return ResponseEntity.ok(screenplayGenerationService.get(tenant().tenantId(), projectId));
    }
}
