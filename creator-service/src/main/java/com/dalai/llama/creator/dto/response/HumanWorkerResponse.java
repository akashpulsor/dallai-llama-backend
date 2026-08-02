package com.dalai.llama.creator.dto.response;

import java.time.OffsetDateTime;

public record HumanWorkerResponse(
        String userId,
        String role,
        String displayName,
        String email,
        boolean online,
        boolean active,
        int activeAssignmentCount,
        long totalAssignmentCount,
        OffsetDateTime lastAssignedAt,
        OffsetDateTime lastSeenAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
