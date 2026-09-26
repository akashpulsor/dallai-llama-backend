package com.dalai.llama.preprod.controller;

import com.dalai.llama.preprod.dto.ShotReferenceImageView;
import com.dalai.llama.preprod.service.ShotReferenceImageService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

/** Multi-image reference bundle for a shot flagged needs_multi_image at scene time (V65 -> V66).
 * Upload / list / delete; the images are read at video-generation time (see PrepareShotContext)
 * and by the storyboard / PDF exporters. All three endpoints are scoped to the caller's tenant
 * -- the service double-checks the shot belongs to the tenant before touching anything. */
@RestController
public class ShotReferenceImageController extends BaseController {

    private final ShotReferenceImageService shotReferenceImageService;

    public ShotReferenceImageController(ShotReferenceImageService shotReferenceImageService) {
        this.shotReferenceImageService = shotReferenceImageService;
    }

    /** Multiple files in one call. Optional {@code captions[]} runs in parallel by index --
     * captions[i] labels files[i], and a missing/blank caption leaves that image un-captioned
     * (the video-gen prompt just references it by ordinal instead). */
    @PostMapping(path = "/v1/shots/{shotId}/reference-images", consumes = "multipart/form-data")
    public ResponseEntity<List<ShotReferenceImageView>> upload(
            @PathVariable UUID shotId,
            @RequestParam("files") List<MultipartFile> files,
            @RequestParam(value = "captions", required = false) List<String> captions) {
        return ResponseEntity.ok(shotReferenceImageService.upload(tenant().tenantId(), shotId, files, captions));
    }

    @GetMapping("/v1/shots/{shotId}/reference-images")
    public ResponseEntity<List<ShotReferenceImageView>> list(@PathVariable UUID shotId) {
        return ResponseEntity.ok(shotReferenceImageService.list(tenant().tenantId(), shotId));
    }

    @DeleteMapping("/v1/shots/{shotId}/reference-images/{imageId}")
    public ResponseEntity<Void> delete(@PathVariable UUID shotId, @PathVariable UUID imageId) {
        shotReferenceImageService.delete(tenant().tenantId(), shotId, imageId);
        return ResponseEntity.noContent().build();
    }
}
