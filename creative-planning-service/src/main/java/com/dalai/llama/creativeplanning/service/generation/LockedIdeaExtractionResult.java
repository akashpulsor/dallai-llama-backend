package com.dalai.llama.creativeplanning.service.generation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Shape of the JSON llm-gateway's LOCKED_IDEA_EXTRACTION task returns. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LockedIdeaExtractionResult(
        String title,
        String concept,
        String targetAudience,
        String campaignAngle,
        String keyMessage,
        String tone
) {
}
