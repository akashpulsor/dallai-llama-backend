package com.dalai.llama.preprod.service.generation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** Shared shape for PRE_PROD_LIGHTING_PLAN_CRITIC and PRE_PROD_CAMERA_PLAN_CRITIC -- unlike
 * {@link ScriptCritiqueResult}, no per-dimension scores: a lighting/camera plan is executable or
 * it isn't, there's no separate "emotional arc" axis worth tracking here. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PlanCritiqueResult(
        String status,
        List<String> issues
) {
    /** What a critic step returns when production's critics are switched off. */
    public static final PlanCritiqueResult PASS = new PlanCritiqueResult("PASS", java.util.List.of());

    public boolean isFail() {
        return "FAIL".equalsIgnoreCase(status);
    }
}
