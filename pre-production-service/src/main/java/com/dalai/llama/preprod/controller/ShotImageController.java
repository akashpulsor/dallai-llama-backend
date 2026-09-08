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

    /** Re-runs vision analysis on an existing image so its on_screen_text / description /
     * on_screen_text_language get refreshed -- without this, images generated before the vision-
     * analysis-on-generate change (V56) have those fields NULL forever and the Download button
     * never surfaces for text-bearing frames. The frontend calls this lazily per-tile when it sees
     * a null on_screen_text; server-side it's just the same describe() call the generate/replace
     * paths already fire, applied to the already-stored bytes. */
    @PostMapping("/v1/shots/{shotId}/images/{kind}/reanalyze")
    public ResponseEntity<ShotImageView> reanalyze(@PathVariable UUID shotId, @PathVariable ShotImageKind kind) {
        return ResponseEntity.ok(shotImageService.reanalyzeVisualDescription(tenant().tenantId(), shotId, kind));
    }

    /** Proactive legacy-data fix: walk every shot_image row in this project whose
     * on_screen_text is still NULL (i.e. was never analyzed under the V56/V83 vision-with-language
     * contract) and re-run describe() on each. Fired once per project per session from the
     * frontend so a creator visiting a project locked before the on_screen_text feature shipped
     * doesn't have to open every tile individually to unlock its Download button. Returns the
     * number of images actually re-analyzed. */
    @PostMapping("/v1/projects/{projectId}/shot-images/reanalyze-missing")
    public ResponseEntity<java.util.Map<String, Integer>> reanalyzeMissingForProject(@PathVariable UUID projectId) {
        int fired = shotImageService.reanalyzeMissingForProject(tenant().tenantId(), projectId);
        return ResponseEntity.ok(java.util.Map.of("reanalyzed", fired));
    }
}
