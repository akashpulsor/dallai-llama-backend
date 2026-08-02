package com.dalai.llama.creator.dto.response;

import java.time.OffsetDateTime;
import java.util.UUID;

public record WorkspaceCheckpointResponse(
        UUID checkpointId,
        Integer version,
        String title,
        OffsetDateTime createdAt
) {
}
