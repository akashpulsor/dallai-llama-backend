package com.dalai.llama.videogen.service;

import com.dalai.llama.videogen.domain.ScenePreparationStatus;
import com.dalai.llama.videogen.domain.entity.ShotPrompt;
import com.dalai.llama.videogen.dto.GenerateShotRequest;
import com.dalai.llama.videogen.dto.ShotPromptView;
import com.dalai.llama.videogen.repository.ShotPromptRepository;
import com.dalai.llama.videogen.service.preproduction.PreProductionServiceClient;
import com.dalai.llama.videogen.service.preproduction.PreProductionViews;
import com.dalai.llama.videogen.web.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Orchestration for prepare-scene: batch shot prepare, per-project prompt listing, project
 * status transitions. Owns the heavy lifting so {@link
 * com.dalai.llama.videogen.controller.PrepareSceneController} stays a thin dispatcher.
 *
 * <p>The batch flow fetches pre-prod's fat prepare-bundle ONCE (one HTTP call carries
 * continuity/config/cast/script + every shot's nested rows) and reuses it across all shots
 * in the loop -- replaces the ~9 per-shot fan-out to pre-prod that was pinning ~90 connections
 * across pre-prod's Hikari pool during a project prepare.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PrepareOrchestrationService {

    private final ScenePreparationService scenePreparationService;
    private final ShotContextAssemblyService shotContextAssemblyService;
    private final ShotGenerationOrchestrator shotGenerationOrchestrator;
    private final ShotPromptRepository shotPromptRepository;
    private final PreProductionServiceClient preProductionClient;

    public BatchResult prepareShotsBatch(TenantContext ctx, UUID projectId, List<UUID> shotIds) {
        if (shotIds == null || shotIds.isEmpty()) {
            throw VideoGenException.badRequest("prepare-batch requires at least one shotId");
        }
        // Pull the fat bundle once and reuse across every shot in the loop -- the whole point of
        // this endpoint over per-shot fan-out. See PreProductionServiceClient.getPrepareBundle.
        PreProductionViews.PrepareBundleView bundle = preProductionClient.getPrepareBundle(ctx.tenantId(), projectId)
                .orElseThrow(() -> VideoGenException.upstream(
                        "pre-production-service returned no prepare bundle for project " + projectId));

        // Toggle project scene preparation status so a UI polling GET /preparation sees
        // PREPARING while the loop runs, READY when it exits cleanly, FAILED on an unhandled
        // abort. Per-shot failures don't push the flag to FAILED -- they live in failed[]; FAILED
        // is reserved for an infrastructure error that broke the loop.
        scenePreparationService.markStatus(ctx.tenantId(), projectId, ScenePreparationStatus.PREPARING);
        long batchStartMs = System.currentTimeMillis();
        log.info("prepare-batch START projectId={} shotCount={} bundleShotsInBundle={}",
                projectId, shotIds.size(), bundle.shots() == null ? 0 : bundle.shots().size());
        List<ShotPromptView> prepared = new ArrayList<>();
        List<FailedShot> failed = new ArrayList<>();
        ShotContextAssemblyService.PrepareShotOverrides overrides =
                new ShotContextAssemblyService.PrepareShotOverrides(null, null, null, null, null);
        try {
            for (UUID shotId : shotIds) {
                long shotStartMs = System.currentTimeMillis();
                try {
                    ShotContextAssemblyService.AssembledShot assembled = shotContextAssemblyService
                            .assembleFromBundle(ctx.tenantId(), projectId, shotId, overrides, bundle);
                    GenerateShotRequest generateRequest = new GenerateShotRequest(
                            projectId, assembled.shotContext(), assembled.featureFlagOverrides(), false);
                    ShotGenerationOrchestrator.PreparedShot preparedShot =
                            shotGenerationOrchestrator.prepareShot(ctx, generateRequest, assembled.sources());
                    ShotPromptView view = shotGenerationOrchestrator.getPrompt(ctx.tenantId(), preparedShot.prompt().getPromptId());
                    prepared.add(view);
                    log.info("prepare-batch shot OK projectId={} shotId={} promptId={} elapsedMs={}",
                            projectId, shotId, preparedShot.prompt().getPromptId(),
                            System.currentTimeMillis() - shotStartMs);
                } catch (RuntimeException ex) {
                    // One shot's failure never stops the batch. Common causes: missing dialogue
                    // beats where the assembler expected them, a shot deleted mid-batch, a
                    // strategy-resolver mismatch on model config.
                    log.warn("prepare-batch shot FAIL projectId={} shotId={} elapsedMs={} errorClass={} errorMessage={}",
                            projectId, shotId, System.currentTimeMillis() - shotStartMs,
                            ex.getClass().getSimpleName(), ex.getMessage());
                    failed.add(new FailedShot(shotId, ex.getMessage()));
                }
            }
            scenePreparationService.markStatus(ctx.tenantId(), projectId, ScenePreparationStatus.READY);
            log.info("prepare-batch END projectId={} preparedCount={} failedCount={} totalElapsedMs={}",
                    projectId, prepared.size(), failed.size(), System.currentTimeMillis() - batchStartMs);
        } catch (RuntimeException ex) {
            scenePreparationService.markStatus(ctx.tenantId(), projectId, ScenePreparationStatus.FAILED);
            log.error("prepare-batch ABORT projectId={} preparedBeforeAbort={} totalElapsedMs={} errorClass={} errorMessage={}",
                    projectId, prepared.size(), System.currentTimeMillis() - batchStartMs,
                    ex.getClass().getSimpleName(), ex.getMessage());
            throw ex;
        }
        return new BatchResult(prepared, failed);
    }

    /** All prepared shot prompts for a project, one row per shot (latest version wins). Video
     * workspace calls this once on page load to render the full editable list without N GETs. */
    public List<ShotPromptView> listProjectShotPrompts(UUID tenantId, UUID projectId) {
        List<ShotPrompt> all = shotPromptRepository.findByProjectIdOrderByJobIdAscCreatedAtDesc(projectId);
        Set<UUID> seenJobs = new HashSet<>();
        List<ShotPromptView> latestPerShot = new ArrayList<>();
        for (ShotPrompt p : all) {
            if (seenJobs.add(p.getJobId())) {
                latestPerShot.add(shotGenerationOrchestrator.getPrompt(tenantId, p.getPromptId()));
            }
        }
        return latestPerShot;
    }

    public record BatchResult(List<ShotPromptView> prepared, List<FailedShot> failed) {}

    public record FailedShot(UUID shotId, String reason) {}
}
