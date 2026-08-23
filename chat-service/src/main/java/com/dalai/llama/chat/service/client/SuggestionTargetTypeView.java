package com.dalai.llama.chat.service.client;

/** Mirrors pre-production-service's own {@code SuggestionTargetTypeView} exactly -- the master-
 * table row shape, fetched live rather than hardcoded here (see {@code
 * PreProductionServiceClient#listSuggestionTargetTypes}). */
public record SuggestionTargetTypeView(
        String code,
        String label,
        String description,
        Boolean requiresTargetRef,
        String targetRefHint
) {
}
