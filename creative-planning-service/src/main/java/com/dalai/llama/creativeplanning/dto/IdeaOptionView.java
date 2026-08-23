package com.dalai.llama.creativeplanning.dto;

import com.dalai.llama.creativeplanning.domain.IdeaOptionSource;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One persisted idea candidate for a standalone {@code ProjectRequirement} -- every option
 * generate() returns is saved (source=GENERATED, parentId=null); saving an edit creates a new
 * row instead (source=EDITED, parentId=the option it was edited from), so the full lineage
 * survives a page refresh instead of living only in a component's in-memory state.
 */
public record IdeaOptionView(
        UUID id,
        String title,
        String concept,
        String targetAudience,
        String campaignAngle,
        String keyMessage,
        String tone,
        IdeaOptionSource source,
        UUID parentId,
        OffsetDateTime createdAt
) {
}
