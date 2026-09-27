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

    /** {@code prompt} is the creator's own description of the music ("indian tense bgm, taut
     * strings, no vocals"). Omit it and the shot's sound design is used as before.
     * {@code lengthSeconds} overrides the shot's duration; the model floor is 3s, so a shorter
     * ask is clamped and trimmed at mix time rather than refused. */
    @PostMapping("/v1/shots/{shotId}/background-music")
    public ResponseEntity<ShotBackgroundMusicView> generateWithPrompt(
            @PathVariable UUID shotId,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String prompt,
            @org.springframework.web.bind.annotation.RequestParam(required = false) Integer lengthSeconds) {
        return ResponseEntity.ok(
                shotBackgroundMusicService.generate(tenant().tenantId(), shotId, prompt, lengthSeconds));
    }

    /** The creator's own track instead of a generated one. */
    @PostMapping(path = "/v1/shots/{shotId}/background-music/upload", consumes = "multipart/form-data")
    public ResponseEntity<ShotBackgroundMusicView> uploadOwn(
            @PathVariable UUID shotId,
            @org.springframework.web.bind.annotation.RequestParam("file") org.springframework.web.multipart.MultipartFile file) {
        return ResponseEntity.ok(shotBackgroundMusicService.uploadOwn(tenant().tenantId(), shotId, file));
    }


    @GetMapping("/v1/shots/{shotId}/background-music")
    public ResponseEntity<ShotBackgroundMusicView> get(@PathVariable UUID shotId) {
        ShotBackgroundMusicView view = shotBackgroundMusicService.get(tenant().tenantId(), shotId);
        return view == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(view);
    }
}
