package com.dalai.llama.creativeplanning.dto;

import com.dalai.llama.creativeplanning.domain.IdeaOptionSource;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * One persisted idea candidate for a standalone {@code ProjectRequirement} -- every option
 * generate() returns is saved (source=GENERATED, parentId=null); saving an edit creates a new
 * row instead (source=EDITED, parentId=the option it was edited from), so the full lineage
 * survives a page refresh instead of living only in a component's in-memory state.
 * <p>
 * {@code criticVerdict}/scores/{@code strengths}/{@code concerns} are critic-service's review of
 * this option (see {@code IdeaCriticServiceClient}) -- shown directly on the card so a creator
 * picking between options sees the actual reasoning behind a score, not just a number. All null
 * if critic-service was unreachable when this option was generated.
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
        String criticVerdict,
        Integer completenessScore,
        Integer storyScore,
        Integer distinctivenessScore,
        List<String> criticStrengths,
        List<String> criticConcerns,
        OffsetDateTime createdAt
) {
}
