package com.dalai.llama.creator.dto.response;

import java.time.OffsetDateTime;
import java.util.UUID;

public record WorkspaceMergeResponse(
        UUID scriptId,
        UUID workspaceId,
        Integer mergedVersion,
        OffsetDateTime mergedAt
) {
}
