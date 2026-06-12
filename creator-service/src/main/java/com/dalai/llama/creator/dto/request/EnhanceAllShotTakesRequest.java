package com.dalai.llama.creator.dto.request;

import java.util.UUID;

public record EnhanceAllShotTakesRequest(
        UUID approvedVariantId,
        String editNote
) {
}
