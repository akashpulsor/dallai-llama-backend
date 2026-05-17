package com.dalai.llama.creator.dto.response;

import java.util.UUID;

public record CreatorCategoryResponse(
        UUID id,
        String code,
        String label,
        String displayName,
        String description,
        String iconKey,
        int sortOrder,
        String promptContext
) {
}
