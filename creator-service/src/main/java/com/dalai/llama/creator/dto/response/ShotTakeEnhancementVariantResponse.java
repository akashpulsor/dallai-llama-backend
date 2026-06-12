package com.dalai.llama.creator.dto.response;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record ShotTakeEnhancementVariantResponse(
        UUID variantId,
        UUID takeId,
        UUID generationJobId,
        UUID previewAssetId,
        String previewUrl,
        UUID finalVideoAssetId,
        String finalVideoUrl,
        UUID finalAudioAssetId,
        String finalAudioUrl,
        UUID finalRenderAssetId,
        String finalRenderUrl,
        String status,
        String provider,
        String providerOperationId,
        Map<String, Object> promptPayload,
        Map<String, Object> providerResponse,
        String userFeedback,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
