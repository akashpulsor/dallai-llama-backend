package com.dalai.llama.llmgateway.service;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks provider calls currently in flight on THIS pod, keyed by job_id, so
 * {@code POST /v1/jobs/{id}/cancel} has something real to cancel. Deliberately in-memory and
 * single-pod -- doc §18 deploys llm-gateway with {@code replicaCount: 1} in v1, so this is
 * complete for the actual topology, not a shortcut around a multi-pod problem that doesn't
 * exist yet. A PROCESSING job not present here (this pod restarted, or -- once replicaCount > 1
 * -- it landed on a different pod) is exactly the case {@code LlmGatewayService}'s staleness
 * check exists to recover.
 */
@Component
public class InFlightJobRegistry {

    private final Map<UUID, CompletableFuture<?>> inFlight = new ConcurrentHashMap<>();

    public void register(UUID jobId, CompletableFuture<?> future) {
        inFlight.put(jobId, future);
        future.whenComplete((result, error) -> inFlight.remove(jobId, future));
    }

    /** @return true if a live call was found and cancelled on this pod. */
    public boolean cancel(UUID jobId) {
        CompletableFuture<?> future = inFlight.get(jobId);
        return future != null && future.cancel(true);
    }

    public boolean isTrackedHere(UUID jobId) {
        return inFlight.containsKey(jobId);
    }
}
