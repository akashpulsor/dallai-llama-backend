package com.dalai.llama.videogen.service;

public interface CostEstimationService {

    /** Calls llm-gateway's real estimate endpoint (design doc §2) -- no cost logic of its own.
     *
     * <p>{@code durationSeconds} is not optional in practice: video models are duration-priced,
     * not token-priced, so their rate cards carry input_token_cost = 0 and an estimate without a
     * duration comes back as $0 for every shot. */
    CostEstimate estimate(String prompt, String modelId, Integer durationSeconds);

    /** Duration-less form -- correct only for genuinely token-priced models. */
    default CostEstimate estimate(String prompt, String modelId) {
        return estimate(prompt, modelId, null);
    }
}
