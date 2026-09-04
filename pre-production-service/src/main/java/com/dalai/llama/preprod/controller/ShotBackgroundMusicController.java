package com.dalai.llama.preprod.controller;

import com.dalai.llama.preprod.dto.ShotBackgroundMusicView;
import com.dalai.llama.preprod.service.ShotBackgroundMusicService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class ShotBackgroundMusicController extends BaseController {

    private final ShotBackgroundMusicService shotBackgroundMusicService;

    public ShotBackgroundMusicController(ShotBackgroundMusicService shotBackgroundMusicService) {
        this.shotBackgroundMusicService = shotBackgroundMusicService;
    }

    @PostMapping("/v1/shots/{shotId}/background-music")
    public ResponseEntity<ShotBackgroundMusicView> generate(@PathVariable UUID shotId) {
        return ResponseEntity.ok(shotBackgroundMusicService.generate(tenant().tenantId(), shotId));
    }

    @GetMapping("/v1/shots/{shotId}/background-music")
    public ResponseEntity<ShotBackgroundMusicView> get(@PathVariable UUID shotId) {
        ShotBackgroundMusicView view = shotBackgroundMusicService.get(tenant().tenantId(), shotId);
        return view == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(view);
    }
}
