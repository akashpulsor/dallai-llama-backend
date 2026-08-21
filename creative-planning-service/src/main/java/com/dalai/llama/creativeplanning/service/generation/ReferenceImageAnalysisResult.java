package com.dalai.llama.creativeplanning.service.generation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Shape of the JSON llm-gateway's REFERENCE_IMAGE_ANALYSIS task returns. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReferenceImageAnalysisResult(
        String description,
        String dominantColors,
        String styleNotes,
        String subjectMatter,
        String suggestedUseCase
) {
}
