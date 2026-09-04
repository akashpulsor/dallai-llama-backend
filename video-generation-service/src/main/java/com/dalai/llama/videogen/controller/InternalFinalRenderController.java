package com.dalai.llama.videogen.controller;

import com.dalai.llama.videogen.service.FinalRenderService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Internal (permitAll, tenantId in the path) alias for pre-production-service's
 * {@code VideoGenServiceClient} to fetch a project's latest assembled final video --
 * powers the client's public review page's final-video section. Same
 * {@code /api/v1/internal/**} convention every other cross-service caller in this
 * codebase uses.
 *
 * <p>Deliberately not merged into {@link FinalRenderController} because that controller is
 * mounted at {@code /v1/final-renders} which is on the JWT-authenticated filter chain --
 * an internal alias there would still 401 without a tenant JWT.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/internal/tenants/{tenantId}/projects/{projectId}/final-video")
public class InternalFinalRenderController {

    private final FinalRenderService finalRenderService;

    @GetMapping("/latest")
    public ResponseEntity<LatestFinalVideoView> latest(@PathVariable UUID tenantId, @PathVariable UUID projectId) {
        return finalRenderService.getLatest(tenantId, projectId)
                .map(job -> {
                    String signed = (job.getOutputBucket() != null && job.getOutputObjectKey() != null)
                            ? finalRenderService.getVideoUrl(tenantId, job.getRenderId())
                            : null;
                    return ResponseEntity.ok(new LatestFinalVideoView(
                            job.getRenderId(),
                            job.getStatus() == null ? null : job.getStatus().name(),
                            signed,
                            job.getCompletedAt()));
                })
                .orElseGet(() -> ResponseEntity.ok(new LatestFinalVideoView(null, null, null, null)));
    }

    public record LatestFinalVideoView(
            UUID renderId,
            String status,
            String videoUrl,
            OffsetDateTime completedAt
    ) {}
}
