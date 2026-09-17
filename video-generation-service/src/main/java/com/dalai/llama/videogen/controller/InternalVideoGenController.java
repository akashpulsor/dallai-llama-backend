package com.dalai.llama.videogen.controller;

import com.dalai.llama.videogen.dto.GenerateShotRequest;
import com.dalai.llama.videogen.dto.GenerateShotResponse;
import com.dalai.llama.videogen.service.ShotGenerationOrchestrator;
import com.dalai.llama.videogen.web.TenantContext;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Sole real caller is pre-production-service's {@code VideoGenClient} -- {@code
 * /api/v1/internal/**} is permitAll (see {@code SecurityConfig}), so tenantId comes from the
 * path, not a JWT-derived context. {@code userId} is null on this path, same as any other
 * request that arrives without a JWT -- {@code ShotGenerationOrchestrator} already tolerates
 * that (see {@code TenantContext}). */
@RestController
@RequestMapping("/api/v1/internal/tenants/{tenantId}")
public class InternalVideoGenController {

    private final ShotGenerationOrchestrator orchestrator;

    public InternalVideoGenController(ShotGenerationOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @PostMapping("/shots/generate")
    public GenerateShotResponse generate(@PathVariable UUID tenantId, @Valid @RequestBody GenerateShotRequest request) {
        return orchestrator.generate(new TenantContext(tenantId, null), request);
    }

    /**
     * The signed URL for a finished job's video, as a value rather than a redirect.
     *
     * <p>The creator-facing {@code GET /v1/jobs/{id}/video} answers this same URL as a 302, which
     * is right for a browser putting it in a {@code <video src>}. It is wrong for another service:
     * {@code /v1/**} requires a JWT, and a service-to-service call made from a background thread
     * has no user token to send, so post-production's dub pipeline got a 401 there. Keyed by
     * job_id because that is the handle the caller already holds -- it discovers the job from
     * {@code /shot-jobs} and never has the shot_id that {@code /clip-source} wants.
     *
     * <p>Tenant ownership is enforced exactly as on the creator-facing route: {@code getVideoUrl}
     * resolves the job by id AND tenant, so a job belonging to another tenant is a 404.
     */
    @GetMapping("/jobs/{jobId}/video-url")
    public ResponseEntity<VideoUrlView> videoUrl(@PathVariable UUID tenantId, @PathVariable UUID jobId) {
        return ResponseEntity.ok(new VideoUrlView(orchestrator.getVideoUrl(tenantId, jobId)));
    }

    /** Just the URL -- a record rather than a bare string so the route can grow a field without
     * breaking the caller's parse. */
    public record VideoUrlView(String url) {
    }
}
