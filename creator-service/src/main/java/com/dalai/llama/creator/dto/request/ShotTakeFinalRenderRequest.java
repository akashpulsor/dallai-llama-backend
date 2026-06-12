package com.dalai.llama.creator.dto.request;

import java.util.Map;
import java.util.UUID;

public record ShotTakeFinalRenderRequest(
        UUID variantId,
        Boolean burnTextOverlays,
        Boolean useMixedAudio,
        Map<String, Object> metadata
) {
}
