package com.dalai.llama.videogen.controller;

import com.dalai.llama.videogen.domain.entity.FinalRenderJob;
import com.dalai.llama.videogen.dto.FinalRenderJobView;
import com.dalai.llama.videogen.service.FinalRenderService;
import com.dalai.llama.videogen.service.VideoGenException;
import com.dalai.llama.videogen.web.TenantContextHolder;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

/**
 * Final-render surface, on the unclaimed {@code /v1/final-renders} prefix -- not
 * {@code /v1/projects} or {@code /v1/shots} (both claimed by pre-production-service too;
 * see infra-platform/charts/backend-service/values.yaml's routing-collision postmortem).
 *
 * <p>Assembly ({@link FinalRenderService#assemble}) blocks on ffmpeg -- the POST holds the
 * connection for the whole render, same synchronous shape {@code POST /v1/jobs/{id}/approve}
 * already uses for per-shot dispatch. Polling endpoints exist for a UI that would rather
 * fire-and-poll, but the blocking POST is the primary path.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/final-renders")
public class FinalRenderController {

    private final FinalRenderService finalRenderService;

    @PostMapping("")
    public ResponseEntity<FinalRenderJobView> create(@Valid @RequestBody CreateFinalRenderRequest request) {
        FinalRenderJob job = finalRenderService.assemble(TenantContextHolder.get(), request.projectId(),
                request.resolveShotAudio(), Boolean.TRUE.equals(request.allowPartial()));
        return ResponseEntity.ok(toView(job));
    }

    @GetMapping("/{renderId}")
    public ResponseEntity<FinalRenderJobView> get(@PathVariable UUID renderId) {
        FinalRenderJob job = finalRenderService.require(TenantContextHolder.get().tenantId(), renderId);
        return ResponseEntity.ok(toView(job));
    }

    /** The UI's stable read: "give me the current final video for this project" without
     * having to track a renderId. Returns 404 if the project has never been assembled. */
    @GetMapping("/projects/{projectId}/latest")
    public ResponseEntity<FinalRenderJobView> getLatest(@PathVariable UUID projectId) {
        return finalRenderService.getLatest(TenantContextHolder.get().tenantId(), projectId)
                .map(job -> ResponseEntity.ok(toView(job)))
                .orElseThrow(() -> VideoGenException.notFound(
                        "Project " + projectId + " has never been assembled"));
    }

    /** 302 straight to a signed MinIO URL -- same pattern as per-shot {@code
     * GET /v1/jobs/{id}/video}; a plain {@code &lt;video src="..."&gt;} works with no
     * intermediate JSON fetch. */
    @GetMapping("/{renderId}/video")
    public ResponseEntity<Void> getVideo(@PathVariable UUID renderId) {
        String signedUrl = finalRenderService.getVideoUrl(TenantContextHolder.get().tenantId(), renderId);
        return ResponseEntity.status(302).location(URI.create(signedUrl)).build();
    }

    private FinalRenderJobView toView(FinalRenderJob job) {
        // Signed MinIO URL directly so the UI can drop it straight into <video src="..."> --
        // avoids a follow-the-302 round-trip the browser would have to make with credentials,
        // same convention getDubbingJob already uses on post-production-service's side.
        String videoUrl = (job.getOutputBucket() != null && job.getOutputObjectKey() != null)
                ? finalRenderService.getVideoUrl(job.getTenantId(), job.getRenderId())
                : null;
        return new FinalRenderJobView(
                job.getRenderId(),
                job.getProjectId(),
                job.getStatus() == null ? null : job.getStatus().name(),
                job.getShotCount(),
                videoUrl,
                job.getLastError(),
                job.getCreatedAt(),
                job.getCompletedAt()
        );
    }

    /** {@code silentShotRefs}: shots whose voice is left out of THIS cut. A render-time choice --
     * the clips themselves are untouched, and the next assembly can include every voice again. Omit
     * or send empty to keep all audio. */
    /**
     * @param shotAudio   per shot_ref, one of CLIP / DUBBED / SILENT. Anything unlisted keeps the
     *                    clip's own audio.
     * @param silentShotRefs the older, narrower way of saying the same thing -- still accepted so a
     *                    caller that only ever dropped voices keeps working. Merged under
     *                    {@code shotAudio}, which wins where both name a shot.
     */
    public record CreateFinalRenderRequest(@NotNull UUID projectId,
                                           java.util.List<String> silentShotRefs,
                                           java.util.Map<String, String> shotAudio,
                                           /** Assemble the shots that are ready and leave out the
                                            * rest. Absent or false refuses instead, naming them. */
                                           Boolean allowPartial) {

        java.util.Map<String, FinalRenderService.ShotAudio> resolveShotAudio() {
            java.util.Map<String, FinalRenderService.ShotAudio> resolved = new java.util.HashMap<>();
            if (silentShotRefs != null) {
                silentShotRefs.forEach(ref -> resolved.put(ref, FinalRenderService.ShotAudio.SILENT));
            }
            if (shotAudio != null) {
                shotAudio.forEach((ref, choice) -> {
                    if (ref == null || choice == null) {
                        return;
                    }
                    try {
                        resolved.put(ref, FinalRenderService.ShotAudio.valueOf(
                                choice.trim().toUpperCase(java.util.Locale.ROOT)));
                    } catch (IllegalArgumentException ex) {
                        throw VideoGenException.badRequest(
                                "Shot " + ref + " was given an audio choice of \"" + choice
                                        + "\" -- it must be CLIP, DUBBED or SILENT");
                    }
                });
            }
            return resolved;
        }
    }
}
