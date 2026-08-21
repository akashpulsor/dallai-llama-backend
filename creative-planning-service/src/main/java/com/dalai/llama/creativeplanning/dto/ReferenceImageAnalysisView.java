package com.dalai.llama.creativeplanning.dto;

public record ReferenceImageAnalysisView(
        String description,
        String dominantColors,
        String styleNotes,
        String subjectMatter,
        String suggestedUseCase
) {
}
