package com.dalai.llama.videogen.service;

import com.dalai.llama.videogen.service.llmgateway.LlmGatewayModelSummary;

import java.util.List;
import java.util.UUID;

public interface ModelRecommendationService {

    /** Skipped entirely by the caller (see {@code ShotGenerationOrchestrator}) if the shot pins
     * {@code technical.targetModel} -- doc §21.6, "Override & pinning." Fetches the video model
     * catalog itself -- fine for a single shot, wasteful in a batch (see the overload below). */
    ModelRecommendation recommend(UUID projectId, ShotSignature shotSignature);

    /** Batch form: caller already fetched the catalog once via {@link #fetchVideoModelCatalog}
     * and passes it in, so N shots in one prepare-batch share one catalog fetch instead of each
     * re-fetching it. The per-shot LLM reasoning call still happens -- different shots (face
     * close-up vs motion-only vs dialogue) can genuinely need different models. */
    ModelRecommendation recommend(UUID projectId, ShotSignature shotSignature, List<LlmGatewayModelSummary> videoModelCatalog);

    /** Fetches the video model catalog once -- call at the top of a batch and pass the result to
     * every {@link #recommend(UUID, ShotSignature, List)} call in that batch. */
    List<LlmGatewayModelSummary> fetchVideoModelCatalog(UUID tenantId);
}
