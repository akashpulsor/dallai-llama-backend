package com.dalai.llama.preprod.dto;

public record SuggestionTargetTypeView(
        String code,
        String label,
        String description,
        Boolean requiresTargetRef,
        String targetRefHint
) {
}
