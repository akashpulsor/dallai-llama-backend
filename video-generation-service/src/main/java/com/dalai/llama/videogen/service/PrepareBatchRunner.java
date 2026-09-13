package com.dalai.llama.videogen.service;

import com.dalai.llama.videogen.domain.ScenePreparationStatus;
import com.dalai.llama.videogen.web.TenantContext;
import com.dalai.llama.videogen.web.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs a prepare-batch off the request thread.
 *
 * <p>Preparing a project used to hold the HTTP connection for the whole batch -- a shot at a time,
 * each with its own LLM round-trips -- so a thirteen-shot project meant minutes on one request and
 * eventually the route timeout, with no partial result to show for it. The batch now runs here and
 * the endpoint returns immediately; each shot's prompt is committed as it completes, so the UI
 * sees the project fill in by polling the prompts it already reads on page load.
 *
 * <p>Deliberately a small fixed pool, and one batch per project at a time. The per-shot loop inside
 * is sequential for a reason (parallel prepares were exhausting pre-production's connection pool),
 * and letting a creator queue three batches for the same project by clicking three times would
 * re-do the same work concurrently and bill for it twice.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PrepareBatchRunner {

    private final PrepareOrchestrationService prepareOrchestrationService;
    private final ScenePreparationService scenePreparationService;

    private final ExecutorService executor = Executors.newFixedThreadPool(2, new ThreadFactory() {
        private final AtomicInteger counter = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "prepare-batch-" + counter.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    });

    private final Set<UUID> inFlightProjects = ConcurrentHashMap.newKeySet();

    /** @return false when this project already has a batch running, so the caller can say so
     *  rather than silently queueing a duplicate. */
    public boolean submit(TenantContext ctx, UUID projectId, List<UUID> shotIds,
                          ShotContextAssemblyService.PrepareShotOverrides overrides) {
        if (!inFlightProjects.add(projectId)) {
            log.info("prepare-batch already running projectId={} -- ignoring duplicate request", projectId);
            return false;
        }
        // Marked here, on the request thread, not inside the task: the caller polls this status
        // immediately after getting its 202, and a task still waiting for a pool thread would
        // otherwise report the previous run's READY and look already finished.
        scenePreparationService.markStatus(ctx.tenantId(), projectId, ScenePreparationStatus.PREPARING);
        try {
            executor.submit(() -> run(ctx, projectId, shotIds, overrides));
            return true;
        } catch (RuntimeException ex) {
            // Rejected before it ever ran -- release the project and un-stick the status, or the
            // UI polls PREPARING forever against a batch that does not exist.
            inFlightProjects.remove(projectId);
            scenePreparationService.markStatus(ctx.tenantId(), projectId, ScenePreparationStatus.FAILED);
            throw ex;
        }
    }

    private void run(TenantContext ctx, UUID projectId, List<UUID> shotIds,
                     ShotContextAssemblyService.PrepareShotOverrides overrides) {
        // TenantContextHolder is a ThreadLocal filled by the servlet filter, so a pool thread has
        // none -- every llm-gateway call downstream would fail on the missing tenant. Carry the
        // request's context across and clear it, since pool threads are reused.
        TenantContextHolder.set(ctx);
        try {
            prepareOrchestrationService.prepareShotsBatch(ctx, projectId, shotIds, overrides);
        } catch (RuntimeException ex) {
            // prepareShotsBatch already marked FAILED and logged the cause; nothing is waiting on
            // this thread, so swallow rather than let it die in the pool's handler.
            log.warn("prepare-batch failed projectId={} errorMessage={}", projectId, ex.getMessage());
        } finally {
            TenantContextHolder.clear();
            inFlightProjects.remove(projectId);
        }
    }
}
