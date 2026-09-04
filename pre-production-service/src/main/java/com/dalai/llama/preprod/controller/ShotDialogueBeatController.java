package com.dalai.llama.preprod.controller;

import com.dalai.llama.preprod.dto.SaveShotDialogueBeatRequest;
import com.dalai.llama.preprod.dto.ShotDialogueBeatView;
import com.dalai.llama.preprod.service.ShotDialogueBeatService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
public class ShotDialogueBeatController extends BaseController {

    private final ShotDialogueBeatService shotDialogueBeatService;

    public ShotDialogueBeatController(ShotDialogueBeatService shotDialogueBeatService) {
        this.shotDialogueBeatService = shotDialogueBeatService;
    }

    @GetMapping("/v1/shots/{shotId}/dialogue-beats")
    public ResponseEntity<List<ShotDialogueBeatView>> list(@PathVariable UUID shotId) {
        return ResponseEntity.ok(shotDialogueBeatService.list(tenant().tenantId(), shotId));
    }

    @PostMapping("/v1/shots/{shotId}/dialogue-beats")
    public ResponseEntity<ShotDialogueBeatView> create(@PathVariable UUID shotId, @Valid @RequestBody SaveShotDialogueBeatRequest request) {
        return ResponseEntity.ok(shotDialogueBeatService.create(tenant().tenantId(), shotId, request));
    }

    @PutMapping("/v1/shots/{shotId}/dialogue-beats/{beatId}")
    public ResponseEntity<ShotDialogueBeatView> update(
            @PathVariable UUID shotId, @PathVariable UUID beatId, @Valid @RequestBody SaveShotDialogueBeatRequest request) {
        return ResponseEntity.ok(shotDialogueBeatService.update(tenant().tenantId(), shotId, beatId, request));
    }

    @DeleteMapping("/v1/shots/{shotId}/dialogue-beats/{beatId}")
    public ResponseEntity<Void> delete(@PathVariable UUID shotId, @PathVariable UUID beatId) {
        shotDialogueBeatService.delete(tenant().tenantId(), shotId, beatId);
        return ResponseEntity.noContent().build();
    }
}
