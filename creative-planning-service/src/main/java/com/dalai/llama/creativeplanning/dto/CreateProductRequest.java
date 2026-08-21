package com.dalai.llama.creativeplanning.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateProductRequest(
        @NotBlank String name,
        String description,
        String category
) {
}
