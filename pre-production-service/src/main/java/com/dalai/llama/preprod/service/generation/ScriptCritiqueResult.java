package com.dalai.llama.preprod.service.generation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** Shape of the JSON llm-gateway's PRE_PROD_SCRIPT_CRITIC task returns. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScriptCritiqueResult(
        String status,
        Integer planAdherenceScore,
        Integer emotionalArcScore,
        Integer dialogueQualityScore,
        Integer narrativeCoherenceScore,
        List<String> issues
) {
    public boolean isFail() {
        return "FAIL".equalsIgnoreCase(status);
    }
}
