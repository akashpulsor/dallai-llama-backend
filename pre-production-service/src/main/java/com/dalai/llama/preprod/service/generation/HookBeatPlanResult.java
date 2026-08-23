package com.dalai.llama.preprod.service.generation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** Shape of the JSON llm-gateway's PRE_PROD_HOOK_BEAT_PLAN_GENERATE task returns. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record HookBeatPlanResult(
        String hookLine,
        List<BeatItem> beats
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BeatItem(
            String title,
            String purpose,
            String emotionalTarget,
            String escalationFromPrevious,
            String payoff
    ) {
    }
}
