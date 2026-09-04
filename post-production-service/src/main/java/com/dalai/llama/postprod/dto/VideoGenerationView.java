package com.dalai.llama.postprod.dto;

public record VideoGenerationView(
        String modelUsed,
        String videoUrl
) {
}
