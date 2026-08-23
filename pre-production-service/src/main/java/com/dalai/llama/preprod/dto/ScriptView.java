package com.dalai.llama.preprod.dto;

import com.dalai.llama.preprod.domain.DraftStatus;

import java.util.List;
import java.util.UUID;

public record ScriptView(
        UUID id,
        UUID projectId,
        DraftStatus status,
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
        List<ScriptCharacterView> characters
) {
}
