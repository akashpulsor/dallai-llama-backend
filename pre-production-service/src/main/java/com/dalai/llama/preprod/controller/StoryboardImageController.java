package com.dalai.llama.preprod.controller;

import com.dalai.llama.preprod.dto.StoryboardImageView;
import com.dalai.llama.preprod.service.StoryboardImageService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class StoryboardImageController extends BaseController {

    private final StoryboardImageService storyboardImageService;

    public StoryboardImageController(StoryboardImageService storyboardImageService) {
        this.storyboardImageService = storyboardImageService;
    }

    @PostMapping("/v1/shots/{shotId}/storyboard-image")
    public ResponseEntity<StoryboardImageView> generate(@PathVariable UUID shotId) {
        return ResponseEntity.ok(storyboardImageService.generate(tenant().tenantId(), shotId));
    }

    @GetMapping("/v1/shots/{shotId}/storyboard-image")
    public ResponseEntity<StoryboardImageView> get(@PathVariable UUID shotId) {
        return ResponseEntity.ok(storyboardImageService.get(tenant().tenantId(), shotId));
    }
}
