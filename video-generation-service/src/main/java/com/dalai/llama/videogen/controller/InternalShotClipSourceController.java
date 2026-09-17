package com.dalai.llama.videogen.controller;

import com.dalai.llama.videogen.service.CloneAudioService;
import com.dalai.llama.videogen.service.dialoguefit.ShotClipRepairService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * What post-production needs to make a new cut of a shot: the clip as it stands, and the recorded
 * take that should go on it.
 *
 * <p>Post-production owns cutting -- replacing the invented audio with the real performance,
 * stripping it, joining the shots -- but it does not own generation, so it cannot read these from
 * its own database and must not read video-generation-service's. This is the seam between the two:
 * one read, no side effects, no knowledge of what the caller intends to do with it.
 *
 * <p>Both URLs are presigned and short-lived, so a caller fetches promptly rather than storing them.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/internal/tenants/{tenantId}/projects/{projectId}")
public class InternalShotClipSourceController {

    private final ShotClipRepairService shotClipRepairService;
    private final CloneAudioService cloneAudioService;
    private final com.dalai.llama.videogen.service.ShotGenerationOrchestrator shotGenerationOrchestrator;

    /**
     * Every shot's latest job in this project -- what another service needs to know which shots
     * have a finished video.
     *
     * <p>Exists because the equivalent creator-facing route is on {@code /v1/**}, which Istio's RBAC
     * refuses for service-to-service calls: post-production asking it got "403 RBAC: access denied"
     * and read the answer as "no shots are generated", so a project with thirteen finished shots
     * reported all thirteen missing from the film. Cross-service reads belong on
     * {@code /api/v1/internal/**}, which is the boundary that is actually open to them.
     */
    @GetMapping("/shot-jobs")
    public ResponseEntity<java.util.List<com.dalai.llama.videogen.dto.VideoGenJobView>> shotJobs(
            @PathVariable UUID tenantId, @PathVariable UUID projectId) {
        return ResponseEntity.ok(shotGenerationOrchestrator.listJobsForProject(tenantId, projectId));
    }

    @GetMapping("/shots/{shotId}/clip-source")
    public ResponseEntity<ShotClipSourceView> clipSource(@PathVariable UUID tenantId,
                                                         @PathVariable UUID projectId,
                                                         @PathVariable UUID shotId) {
        ShotClipRepairService.RepairSources sources = shotClipRepairService.sources(tenantId, projectId, shotId);
        // The most RECENT take, not the longest. A shot accumulates takes -- one against a beat,
        // another against the shot after a re-dub -- and the longest is very often the oldest, so
        // this used to hand back the words a creator had already replaced.
        CloneAudioService.CloneAudioView take =
                CloneAudioService.latestFor(cloneAudioService.list(tenantId, projectId), shotId)
                        .orElse(null);
        return ResponseEntity.ok(new ShotClipSourceView(
                sources.jobId(),
                sources.clipUrl(),
                sources.clipSeconds(),
                take == null ? null : take.audioUrl(),
                take == null || take.durationMs() == null ? null : take.durationMs() / 1000.0,
                take == null ? null : take.text(),
                sources.outputOrigin()));
    }

    /**
     * @param clipUrl        the shot's current clip, presigned.
     * @param dubbedAudioUrl the recorded take, presigned. Null when nothing has been dubbed, which
     *                       the caller has to handle rather than treat as an error -- a shot with no
     *                       line is a perfectly ordinary thing to want silenced.
     */
    public record ShotClipSourceView(UUID jobId,
                                     String clipUrl,
                                     Double clipSeconds,
                                     String dubbedAudioUrl,
                                     Double dubbedAudioSeconds,
                                     String dubbedText,
                                     String outputOrigin) {
    }
}
