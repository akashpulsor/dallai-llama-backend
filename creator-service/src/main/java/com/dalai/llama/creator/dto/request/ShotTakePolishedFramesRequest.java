package com.dalai.llama.creator.dto.request;

import java.util.Map;
import java.util.UUID;

public record ShotTakePolishedFramesRequest(
        UUID variantId,
        Integer sampleCount,
        Boolean persist,
        Map<String, Object> metadata
) {
}
