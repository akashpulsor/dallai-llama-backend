package com.dalai.llama.videogen.controller;

import com.dalai.llama.videogen.service.CloneVoiceService;
import com.dalai.llama.videogen.web.TenantContextHolder;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Video-page "Test voice" preview -- given a shot, returns a short audio sample rendered with the
 * character's real voice identity (cloned from the uploaded actor sample, or direct TTS with the
 * built-in voice pick, exactly matching what {@link com.dalai.llama.videogen.service.BeatDubbingService}
 * would do at approve() time). Lives under a fresh {@code /v1/clone} prefix so it doesn't
 * collide with pre-production-service's {@code /v1/shots} or post-production-service's
 * {@code /v1/dubbing} route ownership at the gateway.
 */
@RestController
public class CloneVoiceController {

    private final CloneVoiceService cloneVoiceService;
    private final com.dalai.llama.videogen.service.DubJobService dubJobService;
    private final com.dalai.llama.videogen.service.CloneAudioService cloneAudioService;

    public CloneVoiceController(CloneVoiceService cloneVoiceService,
                                com.dalai.llama.videogen.service.DubJobService dubJobService,
                                com.dalai.llama.videogen.service.CloneAudioService cloneAudioService) {
        this.cloneVoiceService = cloneVoiceService;
        this.dubJobService = dubJobService;
        this.cloneAudioService = cloneAudioService;
    }

    /**
     * Queues a dub and returns a job id. The page polls that rather than holding a connection.
     *
     * <p>The synchronous {@code /v1/clone} below is kept for the project-wide prepare, which is
     * already a batch the caller waits on; this is the per-shot path a creator presses, where the
     * wait is what made pressing it twice a reasonable thing to do.
     */
    @PostMapping("/v1/clone/jobs")
    public ResponseEntity<DubJobView> queueDub(@RequestBody @NotNull CloneVoiceRequest request) {
        com.dalai.llama.videogen.domain.entity.DubJob job = dubJobService.request(
                TenantContextHolder.get(), request.projectId(), request.shotId(), request.text());
        return ResponseEntity.ok(DubJobView.of(job));
    }

    /**
     * Turns down the most recent take for a shot, or takes the rejection back.
     *
     * <p>Every flow that puts "the dubbed voice" on a clip reaches for the newest take. Without a
     * way to say "not that one", a recording that came out wrong sits as the newest thing there is
     * and every cut made afterwards picks it up. The row is kept, so restoring costs nothing.
     */
    @PostMapping("/v1/clone/shots/{shotId}/reject")
    public ResponseEntity<Void> rejectDub(@org.springframework.web.bind.annotation.PathVariable UUID shotId,
                                          @org.springframework.web.bind.annotation.RequestParam UUID projectId,
                                          @org.springframework.web.bind.annotation.RequestParam(defaultValue = "true")
                                          boolean rejected) {
        cloneAudioService.setRejected(TenantContextHolder.get().tenantId(), projectId, shotId, rejected);
        return ResponseEntity.noContent().build();
    }

    /** Where a queued dub has got to. */
    @org.springframework.web.bind.annotation.GetMapping("/v1/clone/jobs/{jobId}")
    public ResponseEntity<DubJobView> getDub(@org.springframework.web.bind.annotation.PathVariable UUID jobId) {
        UUID tenantId = TenantContextHolder.get().tenantId();
        return dubJobService.get(tenantId, jobId)
                .map(job -> ResponseEntity.ok(DubJobView.of(job)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** What a dub job looks like to the page. */
    public record DubJobView(UUID jobId, UUID shotId, String status, String audioUrl,
                             Integer durationMs, String text, String lastError) {

        static DubJobView of(com.dalai.llama.videogen.domain.entity.DubJob job) {
            return new DubJobView(job.getJobId(), job.getShotId(),
                    job.getStatus() == null ? null : job.getStatus().name(),
                    job.getAudioUrl(), job.getDurationMs(), job.getText(), job.getLastError());
        }
    }

    @PostMapping("/v1/clone")
    public ResponseEntity<CloneVoiceService.CloneVoiceResult> testVoice(@RequestBody @NotNull CloneVoiceRequest request) {
        UUID tenantId = TenantContextHolder.get().tenantId();
        return ResponseEntity.ok(cloneVoiceService.cloneVoice(tenantId, request.projectId(), request.shotId(), request.text()));
    }

    @PostMapping("/v1/clone/project")
    public ResponseEntity<List<CloneVoiceService.CloneVoiceResult>> cloneProject(@RequestBody @NotNull CloneProjectRequest request) {
        UUID tenantId = TenantContextHolder.get().tenantId();
        return ResponseEntity.ok(cloneVoiceService.cloneProject(tenantId, request.projectId()));
    }

    @org.springframework.web.bind.annotation.GetMapping("/v1/clone/projects/{projectId}/audio")
    public ResponseEntity<List<com.dalai.llama.videogen.service.CloneAudioService.CloneAudioView>> savedAudio(
            @org.springframework.web.bind.annotation.PathVariable UUID projectId) {
        UUID tenantId = TenantContextHolder.get().tenantId();
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
                .body(cloneVoiceService.listSavedAudio(tenantId, projectId));
    }

    public record CloneVoiceRequest(UUID projectId, UUID shotId, String text) {}

    public record CloneProjectRequest(UUID projectId) {}
}