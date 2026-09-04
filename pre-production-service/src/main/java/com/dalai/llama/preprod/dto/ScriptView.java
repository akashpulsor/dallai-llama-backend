package com.dalai.llama.preprod.dto;

import com.dalai.llama.preprod.domain.DraftStatus;
import com.dalai.llama.preprod.domain.GenerationSource;

import java.util.List;
import java.util.UUID;

/** {@code currentVersion}/{@code currentSource} describe the latest {@code script_version} row
 * (see that table's javadoc) -- null only for a script generated before version history existed
 * and never regenerated or edited since (the migration backfills one for every pre-existing
 * script, so in practice this is only ever null in a brand-new environment mid-migration). */
public record ScriptView(
        UUID id,
        UUID projectId,
        UUID lockedIdeaId,
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
        String beatPlan,
        String storytellingType,
        List<ScriptCharacterView> characters,
        Integer currentVersion,
        GenerationSource currentSource
) {
}
