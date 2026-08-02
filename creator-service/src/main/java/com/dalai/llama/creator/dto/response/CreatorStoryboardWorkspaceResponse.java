package com.dalai.llama.creator.dto.response;

import java.time.OffsetDateTime;
import java.util.UUID;

public record CreatorStoryboardWorkspaceResponse(
        UUID workspaceId,
        UUID scriptId,
        String status,
        Integer currentVersion,
        String title,
        String conversationSummary,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
