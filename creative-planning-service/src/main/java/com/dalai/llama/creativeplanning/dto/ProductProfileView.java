package com.dalai.llama.creativeplanning.dto;

import java.util.UUID;

public record ProductProfileView(
        UUID id,
        UUID brandContextId,
        String name,
        String description,
        String category
) {
}
