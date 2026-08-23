package com.dalai.llama.preprod.controller;

import com.dalai.llama.preprod.domain.ShotImageKind;
import com.dalai.llama.preprod.dto.ShotImageView;
import com.dalai.llama.preprod.service.ShotImageService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
public class ShotImageController extends BaseController {

    private final ShotImageService shotImageService;

    public ShotImageController(ShotImageService shotImageService) {
        this.shotImageService = shotImageService;
    }

    @PostMapping("/v1/shots/{shotId}/images/{kind}")
    public ResponseEntity<ShotImageView> generate(@PathVariable UUID shotId, @PathVariable ShotImageKind kind) {
        return ResponseEntity.ok(shotImageService.generate(tenant().tenantId(), shotId, kind));
    }

    @GetMapping("/v1/shots/{shotId}/images/{kind}")
    public ResponseEntity<ShotImageView> get(@PathVariable UUID shotId, @PathVariable ShotImageKind kind) {
        return ResponseEntity.ok(shotImageService.get(tenant().tenantId(), shotId, kind));
    }

    @GetMapping("/v1/shots/{shotId}/images")
    public ResponseEntity<List<ShotImageView>> list(@PathVariable UUID shotId) {
        return ResponseEntity.ok(shotImageService.list(tenant().tenantId(), shotId));
    }
}
