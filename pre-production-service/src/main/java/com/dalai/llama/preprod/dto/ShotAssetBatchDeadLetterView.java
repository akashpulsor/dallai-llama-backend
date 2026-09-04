package com.dalai.llama.preprod.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ShotAssetBatchDeadLetterView(
        UUID id,
        UUID shotId,
        String step,
        int attempts,
        String lastError,
        OffsetDateTime createdAt
) {
}
