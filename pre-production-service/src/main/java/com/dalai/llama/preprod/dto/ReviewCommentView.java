package com.dalai.llama.preprod.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ReviewCommentView(
        UUID id,
        String content,
        String imageUrl,
        boolean resolved,
        OffsetDateTime createdAt
) {
}
