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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
    private final ModelRecommendationService modelRecommendationService;
    private final PromptBuilderService promptBuilderService;
    private final ShotPromptRepository shotPromptRepository;
    private final PreProductionServiceClient preProductionClient;

    /**
     * Prepares the given shots, or -- when {@code shotIds} is null/empty -- every shot the project
     * has. "Prepare all shots" is the common UI action and the caller shouldn't have to enumerate
     * ids the bundle already carries; the checkbox selection in the UI is the narrowing case, not
     * the default. Note the ordering: the bundle is the source of truth for "all shots", so the
     * target list can only be resolved after it's fetched.
     *
     * <p>{@code overrides} (dialogue/captions flags, pinned model, resolution) apply to every shot
     * in the batch -- they're the choices the creator makes once for the whole project, not
     * per-shot tweaks.
     */
    public BatchResult prepareShotsBatch(TenantContext ctx, UUID projectId, List<UUID> shotIds,
                                         ShotContextAssemblyService.PrepareShotOverrides overrides) {
        return prepareShotsBatch(ctx, projectId, shotIds, overrides, ProgressListener.NOOP);
    }

    /** Progress form: {@code listener} is told the shot total once it is known and again after
     * every shot, so a caller tracking the batch can report "4 of 13" while it runs instead of
     * only a result at the end. */
    public BatchResult prepareShotsBatch(TenantContext ctx, UUID projectId, List<UUID> shotIds,
                                         ShotContextAssemblyService.PrepareShotOverrides overrides,
                                         ProgressListener listener) {

        // Model catalog first, once for the whole batch -- every shot's recommendation reuses it
        // instead of each shot re-fetching the same list from llm-gateway.
        List<com.dalai.llama.videogen.service.llmgateway.LlmGatewayModelSummary> videoModelCatalog =
                modelRecommendationService.fetchVideoModelCatalog(ctx.tenantId());

        // Pull the fat bundle once and reuse across every shot in the loop -- the whole point of
        // this endpoint over per-shot fan-out. See PreProductionServiceClient.getPrepareBundle.
        PreProductionViews.PrepareBundleView bundle = preProductionClient.getPrepareBundle(ctx.tenantId(), projectId)
                .orElseThrow(() -> VideoGenException.upstream(
                        "pre-production-service returned no prepare bundle for project " + projectId));

        // Most projects pin one video model project-wide (projectConfig.preferredVideoModel), so
        // every shot in the loop resolves to the same modelId. Fetch that model's config (max
        // prompt length) once here, right after we learn what the model is, instead of each shot
        // re-fetching it from llm-gateway. computeIfAbsent inside prepareShot() still covers a
        // shot with its own per-shot model override that isn't this one.
        List<UUID> targetShotIds = shotIds == null || shotIds.isEmpty()
                ? allShotIdsInShootingOrder(bundle)
                : shotIds;
        if (targetShotIds.isEmpty()) {
            log.info("prepare-batch NO-OP projectId={} -- project has no shots to prepare", projectId);
            return new BatchResult(List.of(), List.of());
        }

        Map<String, Integer> maxPromptLengthCache = new HashMap<>();
        // An explicit modelPin in the request beats the project's stored preference -- it's what
        // the creator has selected in the dropdown right now, which may not have been PUT to
        // project-config yet. Same precedence buildTechnical() applies per shot.
        String pinnedModel = overrides == null ? null : overrides.modelPin();
        String preferredVideoModel = pinnedModel != null && !pinnedModel.isBlank()
                ? pinnedModel
                : (bundle.projectConfig() == null ? null : bundle.projectConfig().preferredVideoModel());
        if (preferredVideoModel != null && !preferredVideoModel.isBlank()) {
            maxPromptLengthCache.put(preferredVideoModel, promptBuilderService.maxPromptLengthFor(preferredVideoModel));
        }

        // Toggle project scene preparation status so a UI polling GET /preparation sees
        // PREPARING while the loop runs, READY when it exits cleanly, FAILED on an unhandled
        // abort. Per-shot failures don't push the flag to FAILED -- they live in failed[]; FAILED
        // is reserved for an infrastructure error that broke the loop.
        scenePreparationService.markStatus(ctx.tenantId(), projectId, ScenePreparationStatus.PREPARING);
        // The total is only knowable here: an empty shotIds means "every shot", which the bundle
        // above is what resolves.
        listener.onStart(targetShotIds.size());
        long batchStartMs = System.currentTimeMillis();
        log.info("prepare-batch START projectId={} shotCount={} selection={} bundleShotsInBundle={}",
                projectId, targetShotIds.size(),
                shotIds == null || shotIds.isEmpty() ? "ALL" : "EXPLICIT",
                bundle.shots() == null ? 0 : bundle.shots().size());
        List<ShotPromptView> prepared = new ArrayList<>();
        List<FailedShot> failed = new ArrayList<>();
        ShotContextAssemblyService.PrepareShotOverrides effectiveOverrides = overrides == null
                ? new ShotContextAssemblyService.PrepareShotOverrides(null, null, null, null, null)
                : overrides;
        try {
            for (UUID shotId : targetShotIds) {
                long shotStartMs = System.currentTimeMillis();
                try {
                    ShotContextAssemblyService.AssembledShot assembled = shotContextAssemblyService
                            .assembleFromBundle(ctx.tenantId(), projectId, shotId, effectiveOverrides, bundle);
                    GenerateShotRequest generateRequest = new GenerateShotRequest(
                            projectId, assembled.shotContext(), assembled.featureFlagOverrides(), false);
                    ShotGenerationOrchestrator.PreparedShot preparedShot =
                            shotGenerationOrchestrator.prepareShot(ctx, generateRequest, assembled.sources(),
                                    videoModelCatalog, maxPromptLengthCache);
                    ShotPromptView view = shotGenerationOrchestrator.getPrompt(ctx.tenantId(), preparedShot.prompt().getPromptId());
                    prepared.add(view);
                    log.info("prepare-batch shot OK projectId={} shotId={} promptId={} elapsedMs={}",
                            projectId, shotId, preparedShot.prompt().getPromptId(),
                            System.currentTimeMillis() - shotStartMs);
                    listener.onProgress(prepared.size(), failed.size());
                } catch (RuntimeException ex) {
                    // One shot's failure never stops the batch. Common causes: missing dialogue
                    // beats where the assembler expected them, a shot deleted mid-batch, a
                    // strategy-resolver mismatch on model config.
                    log.warn("prepare-batch shot FAIL projectId={} shotId={} elapsedMs={} errorClass={} errorMessage={}",
                            projectId, shotId, System.currentTimeMillis() - shotStartMs,
                            ex.getClass().getSimpleName(), ex.getMessage());
                    failed.add(new FailedShot(shotId, ex.getMessage()));
                    listener.onProgress(prepared.size(), failed.size());
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

    /** Every shot id in the bundle, ordered by shotNumber so the batch prepares in shooting order
     * and the UI's progress log reads top-to-bottom. Shots without a number sort last rather than
     * blowing up the comparator. */
    private List<UUID> allShotIdsInShootingOrder(PreProductionViews.PrepareBundleView bundle) {
        if (bundle.shots() == null) {
            return List.of();
        }
        return bundle.shots().stream()
                .map(PreProductionViews.ShotBundleView::shot)
                .filter(shot -> shot != null && shot.id() != null)
                .sorted(Comparator.comparing(
                        PreProductionViews.ShotView::shotNumber,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .map(PreProductionViews.ShotView::id)
                .toList();
    }

    /** All prepared shot prompts for a project, one row per shot (latest version wins). The video
     * workspace calls this once on page load so a prepared prompt survives a refresh -- prepare
     * results used to live only in React state, so reloading the page lost every prompt the
     * creator had just paid to build and the cards went back to looking unprepared.
     *
     * <p>Deduped on shot_id, not job_id: re-preparing a shot creates a new job, so a job-keyed
     * dedupe returns the same shot several times and the UI shows a stale prompt for it. Rows
     * predating the shot_id column (V20) fall back to job_id so they still appear exactly once.  */
    public List<ShotPromptView> listProjectShotPrompts(UUID tenantId, UUID projectId) {
        List<ShotPrompt> all = shotPromptRepository.findByProjectIdOrderByCreatedAtDesc(projectId);
        Set<Object> seenShots = new HashSet<>();
        List<ShotPromptView> latestPerShot = new ArrayList<>();
        for (ShotPrompt p : all) {
            Object key = p.getShotId() != null ? p.getShotId() : p.getJobId();
            if (seenShots.add(key)) {
                latestPerShot.add(shotGenerationOrchestrator.getPrompt(tenantId, p.getPromptId()));
            }
        }
        return latestPerShot;
    }

    /** Told the shot total once resolved, then after each shot finishes either way. Implementations
     * must not throw: a failure to record progress is not a reason to abandon the batch. */
    public interface ProgressListener {
        ProgressListener NOOP = new ProgressListener() {
            @Override public void onStart(int totalShots) {}
            @Override public void onProgress(int prepared, int failed) {}
        };

        void onStart(int totalShots);

        void onProgress(int prepared, int failed);
    }

    public record BatchResult(List<ShotPromptView> prepared, List<FailedShot> failed) {}

    public record FailedShot(UUID shotId, String reason) {}
}
