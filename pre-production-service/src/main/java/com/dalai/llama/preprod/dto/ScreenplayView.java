package com.dalai.llama.preprod.dto;

import com.dalai.llama.preprod.domain.DraftStatus;
import com.dalai.llama.preprod.domain.GenerationSource;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record ScreenplayView(
        UUID id,
        UUID projectId,
        UUID scriptId,
        UUID lockedIdeaId,
        DraftStatus status,
        Integer version,
        GenerationSource source,
        UUID parentId,
        OffsetDateTime createdAt,
        List<ScreenplaySceneView> scenes
) {
}
