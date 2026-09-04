package com.dalai.llama.preprod.dto;

import com.dalai.llama.preprod.domain.GenerationSource;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ScriptVersionView(
        UUID id,
        UUID projectId,
        UUID scriptId,
        Integer version,
        GenerationSource source,
        UUID parentId,
        String scriptText,
        String pacingStyle,
        String emotionalArc,
        String hookStrategy,
        Boolean noHumans,
        String logline,
        String centralConflict,
        String endingPayoff,
        String setting,
        String hook,
        String storytellingType,
        String critiqueNotes,
        OffsetDateTime createdAt
) {
}
