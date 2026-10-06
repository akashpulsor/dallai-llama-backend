package com.dalai.llama.postprod.controller;

import com.dalai.llama.postprod.domain.SoundLayerKind;
import com.dalai.llama.postprod.dto.GenerateSoundLayerRequest;
import com.dalai.llama.postprod.dto.SoundLayerView;
import com.dalai.llama.postprod.dto.UpdateSoundLayerRequest;
import com.dalai.llama.postprod.service.sound.SoundLayerService;
import com.dalai.llama.postprod.web.TenantContext;
import com.dalai.llama.postprod.web.TenantContextHolder;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

/** Music cues and sound effects on the film's timeline, each anchored to a shot. Mixed into the
 * film when it renders; switching one off or moving it needs no clip to be remade. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/post-production/projects/{projectId}/sound-layers")
public class SoundLayerController {

    private final SoundLayerService soundLayerService;

    @GetMapping
    public ResponseEntity<List<SoundLayerView>> list(@PathVariable UUID projectId) {
        return ResponseEntity.ok(soundLayerService.list(TenantContextHolder.get().tenantId(), projectId));
    }

    /** Generate one from a description -- "a single temple bell, long ring". Answers 202 with the
     * layer QUEUED; poll the list until it is COMPLETED (or FAILED, with its reason). */
    @PostMapping
    public ResponseEntity<SoundLayerView> generate(@PathVariable UUID projectId,
                                                   @Valid @RequestBody GenerateSoundLayerRequest request) {
        TenantContext ctx = TenantContextHolder.get();
        return ResponseEntity.accepted().body(soundLayerService.generate(ctx.tenantId(), projectId, ctx.userId(), request));
    }

    /** Upload your own file. Stored as it arrives; checked for sound on the worker side, so this
     * also answers 202 with the layer QUEUED. */
    @PostMapping(path = "/upload", consumes = "multipart/form-data")
    public ResponseEntity<SoundLayerView> upload(@PathVariable UUID projectId,
                                                 @RequestParam UUID shotId,
                                                 @RequestParam SoundLayerKind kind,
                                                 @RequestParam(required = false) Integer offsetMs,
                                                 @RequestParam("file") MultipartFile file) {
        TenantContext ctx = TenantContextHolder.get();
        return ResponseEntity.accepted().body(soundLayerService.upload(ctx.tenantId(), projectId, ctx.userId(), shotId, kind, offsetMs, file));
    }

    /** Move it, change its level or fades, or switch it in or out of the film. */
    @PatchMapping("/{layerId}")
    public ResponseEntity<SoundLayerView> update(@PathVariable UUID projectId, @PathVariable UUID layerId,
                                                 @Valid @RequestBody UpdateSoundLayerRequest request) {
        return ResponseEntity.ok(soundLayerService.update(TenantContextHolder.get().tenantId(), projectId, layerId, request));
    }

    @DeleteMapping("/{layerId}")
    public ResponseEntity<Void> delete(@PathVariable UUID projectId, @PathVariable UUID layerId) {
        soundLayerService.delete(TenantContextHolder.get().tenantId(), projectId, layerId);
        return ResponseEntity.noContent().build();
    }
}
