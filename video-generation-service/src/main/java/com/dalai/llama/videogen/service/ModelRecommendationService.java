package com.dalai.llama.videogen.service;

public interface ModelRecommendationService {

    /** Skipped entirely by the caller (see {@code ShotGenerationOrchestrator}) if the shot pins
     * {@code technical.targetModel} -- doc §21.6, "Override & pinning." */
    ModelRecommendation recommend(ShotSignature shotSignature);
}
