package com.dalai.llama.preprod.dto;

import java.time.OffsetDateTime;

public record GenerationThoughtView(
        String step,
        String message,
        OffsetDateTime createdAt
) {
}
