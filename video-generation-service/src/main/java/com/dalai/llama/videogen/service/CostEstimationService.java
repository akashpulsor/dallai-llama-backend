package com.dalai.llama.videogen.service;

public interface CostEstimationService {

    /** Calls llm-gateway's real {@code POST /v1/estimate} (design doc §2) -- no cost logic of
     * its own. */
    CostEstimate estimate(String prompt, String modelId);
}
