package com.dalai.llama.preprod.controller;

import com.dalai.llama.preprod.domain.ShotImageKind;
import com.dalai.llama.preprod.dto.SetContinuityOverrideRequest;
import com.dalai.llama.preprod.dto.ShotImageView;
import com.dalai.llama.preprod.dto.StepContinuityView;
import com.dalai.llama.preprod.service.ShotImageBundle;
import com.dalai.llama.preprod.service.ShotImageService;
import com.dalai.llama.preprod.service.continuity.StepContinuityService;
import com.dalai.llama.preprod.service.continuity.VisualField;
import jakarta.validation.Valid;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@RestController
public class ShotImageController extends BaseController {

    private final ShotImageService shotImageService;
    private final StepContinuityService stepContinuityService;

    public ShotImageController(ShotImageService shotImageService, StepContinuityService stepContinuityService) {
        this.shotImageService = shotImageService;
        this.stepContinuityService = stepContinuityService;
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

    /** "Step shot": make this shot's image as the next moment of an earlier shot, by editing that
     * shot's image -- characters who stay keep their exact look, the others leave the frame. */
    @PostMapping("/v1/shots/{shotId}/images/{kind}/step-from/{sourceShotId}")
    public ResponseEntity<ShotImageView> stepFrom(
            @PathVariable UUID shotId, @PathVariable ShotImageKind kind, @PathVariable UUID sourceShotId,
            @RequestParam(required = false) String note) {
        return ResponseEntity.ok(shotImageService.generateStepFrom(tenant().tenantId(), shotId, kind, sourceShotId, note));
    }

    /** The same step as a zip (prompt.txt + numbered images + README) for an outside image tool;
     * the result comes back through {@link #replace}. POST, not GET: building it can make one
     * billed text call (the identity reliability rewrite). */
    @PostMapping("/v1/shots/{shotId}/images/{kind}/step-from/{sourceShotId}/bundle")
    public ResponseEntity<byte[]> stepBundle(
            @PathVariable UUID shotId, @PathVariable ShotImageKind kind, @PathVariable UUID sourceShotId,
            @RequestParam(required = false) String note) {
        ShotImageBundle bundle = shotImageService.stepBundle(tenant().tenantId(), shotId, kind, sourceShotId, note);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(bundle.fileName()).build().toString())
                .body(bundle.zip());
    }

    /** The step's continuity resolution and final prompt, before generating: what is kept from the
     * earlier shot, what this shot changes, and every automatic override with its reason. */
    @PostMapping("/v1/shots/{shotId}/images/{kind}/step-from/{sourceShotId}/continuity")
    public ResponseEntity<StepContinuityView> stepContinuity(
            @PathVariable UUID shotId, @PathVariable ShotImageKind kind, @PathVariable UUID sourceShotId,
            @RequestParam(required = false) String note) {
        return ResponseEntity.ok(shotImageService.previewStep(tenant().tenantId(), shotId, kind, sourceShotId, note));
    }

    /** The user's own value for one visual field, deliberately winning over continuity. The caller
     * re-requests {@link #stepContinuity} to get the recomputed state and prompt. */
    @PutMapping("/v1/shots/{shotId}/continuity-overrides/{field}")
    public ResponseEntity<Void> setContinuityOverride(@PathVariable UUID shotId, @PathVariable VisualField field,
                                                      @Valid @RequestBody SetContinuityOverrideRequest request) {
        stepContinuityService.setOverride(tenant().tenantId(), shotId, field, request.value());
        return ResponseEntity.noContent().build();
    }

    /** Back to the automatic continuity decision for that field. */
    @DeleteMapping("/v1/shots/{shotId}/continuity-overrides/{field}")
    public ResponseEntity<Void> clearContinuityOverride(@PathVariable UUID shotId, @PathVariable VisualField field) {
        stepContinuityService.clearOverride(tenant().tenantId(), shotId, field);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/v1/shots/{shotId}/images/{kind}")
    public ResponseEntity<Void> delete(@PathVariable UUID shotId, @PathVariable ShotImageKind kind) {
        shotImageService.deleteImage(tenant().tenantId(), shotId, kind);
        return ResponseEntity.noContent().build();
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
