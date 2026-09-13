package com.dalai.llama.videogen.service;

import java.util.UUID;

public interface ModelRecommendationService {

    /** Skipped entirely by the caller (see {@code ShotGenerationOrchestrator}) if the shot pins
     * {@code technical.targetModel} -- doc §21.6, "Override & pinning." */
    ModelRecommendation recommend(UUID projectId, ShotSignature shotSignature);
}
