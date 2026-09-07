package com.dalai.llama.preprod.controller;

import com.dalai.llama.preprod.domain.ShotImageKind;
import com.dalai.llama.preprod.dto.ShotImageView;
import com.dalai.llama.preprod.service.ShotImageService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

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

    /** "Attach a reference photo and ask for a change" -- e.g. match this photo's lighting/pose.
     * Edits the shot's current {@code kind} image using the current image plus every attached
     * file as input, in one Gemini call; nothing is kept from the uploads themselves. */
    @PostMapping(path = "/v1/shots/{shotId}/images/{kind}/inspiration", consumes = "multipart/form-data")
    public ResponseEntity<ShotImageView> generateWithInspiration(
            @PathVariable UUID shotId, @PathVariable ShotImageKind kind,
            @RequestParam(required = false) String note, @RequestParam("files") List<MultipartFile> files) {
        return ResponseEntity.ok(shotImageService.generateWithInspiration(tenant().tenantId(), shotId, kind, note, files));
    }

    /** "Same" upload flow -- creator downloaded the image, hand-corrected the typos (Gemini's
     * text rendering is unreliable, especially in Hindi/Hinglish), and is uploading the fixed
     * version. No LLM call: the uploaded bytes ARE the new image, stored as-is. Sibling of
     * {@link #generateWithInspiration}, which is the "inspired" variant of the same UX. */
    @PostMapping(path = "/v1/shots/{shotId}/images/{kind}/replace", consumes = "multipart/form-data")
    public ResponseEntity<ShotImageView> replace(
            @PathVariable UUID shotId, @PathVariable ShotImageKind kind,
            @RequestParam("file") MultipartFile file) {
        return ResponseEntity.ok(shotImageService.replaceImage(tenant().tenantId(), shotId, kind, file));
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
