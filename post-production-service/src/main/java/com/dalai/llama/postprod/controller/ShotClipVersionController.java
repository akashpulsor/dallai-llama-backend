package com.dalai.llama.postprod.controller;

import com.dalai.llama.postprod.domain.entity.ShotClipVersion;
import com.dalai.llama.postprod.dto.ShotClipVersionView;
import com.dalai.llama.postprod.service.clip.ShotClipVersionService;
import com.dalai.llama.postprod.web.TenantContext;
import com.dalai.llama.postprod.web.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

/**
 * Cutting a shot: make a version, watch it, then decide whether the film uses it.
 *
 * <p>On {@code /v1/post-production/**}, the prefix this service already owns at the gateway. Not
 * {@code /v1/shots} or {@code /v1/projects} -- both are claimed by pre-production-service, and two
 * services on one prefix is an Istio route collision with an undefined winner, which this platform
 * has already been bitten by more than once.
 *
 * <p>Every make-a-cut call returns a PREVIEW. Nothing here changes what the film uses except
 * {@code accept}, which is the point: a cut used to replace the clip the moment it was produced, so
 * the only way to find out whether it was any good was to lose the alternative.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/post-production/projects/{projectId}/shots/{shotId}/clip-versions")
public class ShotClipVersionController {

    private final ShotClipVersionService clipVersionService;

    /** Every cut of this shot, newest first. */
    @GetMapping
    public ResponseEntity<List<ShotClipVersionView>> list(@PathVariable UUID projectId,
                                                          @PathVariable UUID shotId) {
        TenantContext ctx = TenantContextHolder.get();
        return ResponseEntity.ok(toViews(clipVersionService.list(ctx.tenantId(), shotId)));
    }

    /**
     * Makes sure this shot has version 1 -- its generated clip -- and returns it.
     *
     * <p>Versions are created lazily, the first time a shot is cut, so a shot a creator is happy
     * with has no rows at all and nothing to download, publish or point at. This is what the page
     * calls the first time any of those is wanted.
     */
    @PostMapping("/baseline")
    public ResponseEntity<ShotClipVersionView> baseline(@PathVariable UUID projectId,
                                                        @PathVariable UUID shotId,
                                                        @RequestParam(required = false) String shotRef) {
        return ResponseEntity.ok(toView(
                clipVersionService.importGeneratedBaseline(context(projectId, shotId, shotRef))));
    }

    /** The picture with the recorded take in place of whatever audio it came with. */
    @PostMapping("/dubbed")
    public ResponseEntity<ShotClipVersionView> dubbed(@PathVariable UUID projectId,
                                                      @PathVariable UUID shotId,
                                                      @RequestParam(required = false) String shotRef) {
        return ResponseEntity.ok(toView(clipVersionService.createDubbedPreview(context(projectId, shotId, shotRef))));
    }

    /** The picture with no voice at all. */
    @PostMapping("/silent")
    public ResponseEntity<ShotClipVersionView> silent(@PathVariable UUID projectId,
                                                      @PathVariable UUID shotId,
                                                      @RequestParam(required = false) String shotRef) {
        return ResponseEntity.ok(toView(clipVersionService.createSilentPreview(context(projectId, shotId, shotRef))));
    }

    /** A cut the creator made themselves and brought back. */
    @PostMapping("/uploaded")
    public ResponseEntity<ShotClipVersionView> uploaded(@PathVariable UUID projectId,
                                                        @PathVariable UUID shotId,
                                                        @RequestParam(required = false) String shotRef,
                                                        @RequestParam(required = false) UUID editedFromVersionId,
                                                        @RequestParam("file") MultipartFile file) {
        return ResponseEntity.ok(toView(clipVersionService.createUploadedPreview(
                context(projectId, shotId, shotRef), file, editedFromVersionId)));
    }

    /**
     * Records that this cut has been taken away to be edited.
     *
     * <p>Called by the download button. Downloading used to leave no trace, so the only shots a
     * creator could account for were the ones already back -- one taken away days ago looked exactly
     * like one nobody had touched.
     */
    @PostMapping("/{versionId}/checkout")
    public ResponseEntity<ShotClipVersionView> checkout(@PathVariable UUID projectId,
                                                        @PathVariable UUID shotId,
                                                        @PathVariable UUID versionId) {
        TenantContext ctx = TenantContextHolder.get();
        return ResponseEntity.ok(toView(
                clipVersionService.markDownloadedForEdit(ctx.tenantId(), shotId, versionId, ctx.userId())));
    }

    /** Show this shot to the client on its own, or take it back down. Separate from publishing the
     * film: a creator often wants one shot in front of a client long before a film exists. */
    @PostMapping("/{versionId}/publish")
    public ResponseEntity<ShotClipVersionView> publish(@PathVariable UUID projectId,
                                                       @PathVariable UUID shotId,
                                                       @PathVariable UUID versionId,
                                                       @RequestParam(defaultValue = "true") boolean published) {
        TenantContext ctx = TenantContextHolder.get();
        return ResponseEntity.ok(toView(
                clipVersionService.setPublished(ctx.tenantId(), shotId, versionId, published)));
    }

    /** Make this cut the one the film uses. The cut it replaces is kept, so this goes both ways. */
    @PostMapping("/{versionId}/accept")
    public ResponseEntity<ShotClipVersionView> accept(@PathVariable UUID projectId,
                                                      @PathVariable UUID shotId,
                                                      @PathVariable UUID versionId) {
        TenantContext ctx = TenantContextHolder.get();
        return ResponseEntity.ok(toView(clipVersionService.accept(ctx.tenantId(), shotId, versionId)));
    }

    private ShotClipVersionService.Context context(UUID projectId, UUID shotId, String shotRef) {
        TenantContext ctx = TenantContextHolder.get();
        return new ShotClipVersionService.Context(ctx.tenantId(), projectId, shotId, shotRef, ctx.userId());
    }

    private ShotClipVersionView toView(ShotClipVersion version) {
        return ShotClipVersionView.of(version, clipVersionService.playableUrl(version));
    }

    private List<ShotClipVersionView> toViews(List<ShotClipVersion> versions) {
        return versions.stream().map(this::toView).toList();
    }
}
