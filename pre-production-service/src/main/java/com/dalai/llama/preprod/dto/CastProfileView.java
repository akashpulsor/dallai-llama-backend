package com.dalai.llama.preprod.dto;

import java.util.UUID;

public record CastProfileView(
        UUID id,
        UUID projectId,
        String displayName,
        String faceRefBucket,
        String faceRefObjectKey,
        String description
) {
}
